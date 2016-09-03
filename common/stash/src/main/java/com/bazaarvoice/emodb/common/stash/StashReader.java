package com.bazaarvoice.emodb.common.stash;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.regions.Region;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.S3ClientOptions;
import com.amazonaws.services.s3.model.ListObjectsRequest;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.bazaarvoice.emodb.sor.api.StashNotAvailableException;
import com.bazaarvoice.emodb.sor.api.TableNotStashedException;
import com.google.common.base.Predicate;
import com.google.common.collect.AbstractIterator;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.google.common.collect.PeekingIterator;
import com.google.common.collect.Range;

import javax.annotation.Nullable;
import java.io.InputStream;
import java.net.URI;
import java.util.Iterator;
import java.util.List;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Provides basic access to Stashed tables and content.
 */
abstract public class StashReader {

    protected final AmazonS3 _s3;
    protected final String _bucket;
    protected final String _rootPath;

    protected StashReader(URI stashRoot, AmazonS3 s3) {
        checkNotNull(stashRoot, "stashRoot");
        _s3 = checkNotNull(s3, "s3");
        _bucket = stashRoot.getHost();

        String path = stashRoot.getPath();
        if (path == null) {
            path = "";
        } else if (path.startsWith("/")) {
            // S3 paths don't have a leading slash
            path = path.substring(1);
        }
        _rootPath = path;
    }

    /**
     * Returns the root path; immediate subdirectories of this path are tables.  This must always be  prefixed by
     * _rootPath:
     *
     * <code>
     *     assert getRootPath().startsWith(_rootPath);
     * </code>
     */
    abstract protected String getRootPath();

    /**
     * Utility method to get the S3 client for a credentials provider.
     */
    protected static AmazonS3 getS3Client(URI stashRoot, AWSCredentialsProvider credentialsProvider) {
        return getS3Client(stashRoot, credentialsProvider, null);
    }

    protected static AmazonS3 getS3Client(URI stashRoot, AWSCredentialsProvider credentialsProvider,
                                          @Nullable ClientConfiguration s3Config) {
        AmazonS3 s3;
        if (s3Config == null) {
            s3 = new AmazonS3Client(credentialsProvider);
        } else {
            s3 = new AmazonS3Client(credentialsProvider, s3Config);
        }
        // Determine the region for the bucket
        Region region = StashUtil.getRegionForBucket(stashRoot.getHost());
        s3.setRegion(region);

        String awsEndpoint = System.getProperty("AWS_ENDPOINT");
        if (awsEndpoint != null) {
            s3.setEndpoint(awsEndpoint);
        }

        boolean withPathStyleAccess = Boolean.getBoolean("AWS_WITH_PATH_STYLE_ACCESS");
        if (withPathStyleAccess) {
            s3.setS3ClientOptions(new S3ClientOptions().withPathStyleAccess(true));
        }

        return s3;
    }

    /**
     * Gets all tables available in this stash.
     */
    public Iterator<StashTable> listTables() {
        final String root = getRootPath();
        final String prefix = String.format("%s/", root);

        return new AbstractIterator<StashTable>() {
            Iterator<String> _commonPrefixes = Iterators.emptyIterator();
            String _marker = null;
            boolean _truncated = true;

            @Override
            protected StashTable computeNext() {
                String dir = null;

                while (dir == null) {
                    if (_commonPrefixes.hasNext()) {
                        dir = _commonPrefixes.next();
                        if (dir.isEmpty()) {
                            // Ignore the empty directory if it comes back
                            dir = null;
                        } else {
                            // Strip the prefix and trailing "/"
                            dir = dir.substring(prefix.length(), dir.length()-1);
                        }
                    } else if (_truncated) {
                        ObjectListing response = _s3.listObjects(new ListObjectsRequest()
                                .withBucketName(_bucket)
                                .withPrefix(prefix)
                                .withDelimiter("/")
                                .withMarker(_marker)
                                .withMaxKeys(1000));

                        _commonPrefixes = response.getCommonPrefixes().iterator();
                        _marker = response.getNextMarker();
                        _truncated = response.isTruncated();
                    } else {
                        return endOfData();
                    }
                }

                String tablePrefix = prefix + dir + "/";
                String tableName = StashUtil.decodeStashTable(dir);
                return new StashTable(_bucket, tablePrefix, tableName);
            }
        };
    }

    /**
     * Gets the metadata for all tables in this stash.  This is a heavier operation that just {@link #listTables()}
     * since it also returns full file details for the entire Stash instead of just table names.
     */
    public Iterator<StashTableMetadata> listTableMetadata() {
        final String root = getRootPath();
        final String prefix = String.format("%s/", root);
        final int prefixLength = prefix.length();

        return new AbstractIterator<StashTableMetadata>() {
            PeekingIterator<S3ObjectSummary> _listResponse =
                    Iterators.peekingIterator(Iterators.<S3ObjectSummary>emptyIterator());
            String _marker = null;
            boolean _truncated = true;

            @Override
            protected StashTableMetadata computeNext() {
                String tableDir = null;
                List<StashFileMetadata> files = Lists.newArrayListWithCapacity(16);
                boolean allFilesRead = false;

                while (!allFilesRead) {
                    if (_listResponse.hasNext()) {
                        // Peek at the next record but don't consume it until we verify it's part of the same table
                        S3ObjectSummary s3File = _listResponse.peek();
                        String key = s3File.getKey();

                        // Don't include the _SUCCESS file or any other stray files we may find
                        String[] parentDirAndFile = key.substring(prefixLength).split("/");
                        if (parentDirAndFile.length != 2) {
                            // Consume and skip this row
                            _listResponse.next();
                        } else {
                            String parentDir = parentDirAndFile[0];
                            if (tableDir == null) {
                                tableDir = parentDir;
                            }

                            if (!parentDir.equals(tableDir)) {
                                allFilesRead = true;
                            } else {
                                // Record is part of this table; consume it now
                                _listResponse.next();
                                files.add(new StashFileMetadata(_bucket, key, s3File.getSize()));
                            }
                        }
                    } else if (_truncated) {
                        ObjectListing response = _s3.listObjects(new ListObjectsRequest()
                                .withBucketName(_bucket)
                                .withPrefix(prefix)
                                .withMarker(_marker)
                                .withMaxKeys(1000));

                        _listResponse = Iterators.peekingIterator(response.getObjectSummaries().iterator());
                        _marker = response.getNextMarker();
                        _truncated = response.isTruncated();
                    } else {
                        allFilesRead = true;
                    }
                }

                if (tableDir == null) {
                    // No files read this iteration means all files have been read
                    return endOfData();
                }

                String tablePrefix = prefix + tableDir + "/";
                String tableName = StashUtil.decodeStashTable(tableDir);
                return new StashTableMetadata(_bucket, tablePrefix, tableName, files);
            }
        };
    }

    /**
     * Gets the metadata for a single table in this stash.  This is similar to getting the splits for the table
     * except that it exposes lower level information about the underlying S3 files.  For clients who will use
     * their own system for reading the files from S3, such as source files for a map-reduce job, this method provides
     * the necessary information.  For simply iterating over the stash contents using either {@link #scan(String)}
     * or {@link #getSplits(String)} in conjunction with {@link #getSplit(StashSplit)} is preferred.
     */
    public StashTableMetadata getTableMetadata(String table)
            throws StashNotAvailableException, TableNotStashedException {
        ImmutableList.Builder<StashFileMetadata> filesBuilder = ImmutableList.builder();

        Iterator<S3ObjectSummary> objectSummaries = getS3ObjectSummariesForTable(table);
        while (objectSummaries.hasNext()) {
            S3ObjectSummary objectSummary = objectSummaries.next();
            filesBuilder.add(new StashFileMetadata(_bucket, objectSummary.getKey(), objectSummary.getSize()));
        }

        List<StashFileMetadata> files = filesBuilder.build();

        // Get the prefix arbitrarily from the first file.
        String prefix = files.get(0).getKey();
        prefix = prefix.substring(0, prefix.lastIndexOf('/') + 1);

        return new StashTableMetadata(_bucket, prefix, table, files);
    }

    public boolean getTableExists(String table) {
        return getS3ObjectSummariesForTable(table).hasNext();
    }

    /**
     * Get the splits for a record stored in stash.  Each split corresponds to a file in the Stash table's directory.
     */
    public List<StashSplit> getSplits(String table)
            throws StashNotAvailableException, TableNotStashedException {
        ImmutableList.Builder<StashSplit> splitsBuilder = ImmutableList.builder();

        Iterator<S3ObjectSummary> objectSummaries = getS3ObjectSummariesForTable(table);
        while (objectSummaries.hasNext()) {
            S3ObjectSummary objectSummary = objectSummaries.next();
            String key = objectSummary.getKey();
            // Strip the common root path prefix from the split since it is constant.
            splitsBuilder.add(new StashSplit(table, key.substring(_rootPath.length() + 1), objectSummary.getSize()));
        }

        return splitsBuilder.build();
    }

    /**
     * Gets an iterator over the contents of a split returned by {@link #getSplits(String)}.  If possible the caller
     * should call {@link com.bazaarvoice.emodb.common.stash.StashRowIterator#close()} when done with the iterator
     * to immediately free any S3 connections.
     */
    public StashRowIterator getSplit(final StashSplit split) {
        return new StashSplitIterator(_s3, _bucket, getSplitKey(split));
    }

    /**
     * Gets an iterator over the entire contents of a Stash table.  If possible the caller should call
     * {@link com.bazaarvoice.emodb.common.stash.StashRowIterator#close()} when done with the iterator
     * to immediately free any S3 connections.
     */
    public StashRowIterator scan(String table)
            throws StashNotAvailableException, TableNotStashedException {
        List<StashSplit> splits = getSplits(table);
        return new StashScanIterator(_s3, _bucket, _rootPath, splits);
    }

    private String getSplitKey(StashSplit split) {
        // The key in the split has the root removed, so we need to put it back.
        return String.format("%s/%s", _rootPath, split.getKey());
    }

    // The following methods are fairly low level and do not typically need to be accessed

    public InputStream getRawSplit(StashSplit split) {
        return new RestartingS3InputStream(_s3, _bucket, getSplitKey(split));
    }

    public InputStream getRawSplitPart(StashSplit split, Range<Long> byteRange) {
        return new RestartingS3InputStream(_s3, _bucket, getSplitKey(split), byteRange);
    }

    private String getPrefix(String table) {
        String root = getRootPath();
        return String.format("%s/%s/", root, StashUtil.encodeStashTable(table));
    }

    private Iterator<S3ObjectSummary> getS3ObjectSummariesForTable(String table)
            throws TableNotStashedException {
        String prefix = getPrefix(table);
        Iterator<S3ObjectSummary> summaryIterator = getS3ObjectSummaries(prefix);

        if (!summaryIterator.hasNext()) {
            throw new TableNotStashedException(table);
        }

        return summaryIterator;
    }

    private Iterator<S3ObjectSummary> getS3ObjectSummaries(final String prefix) {
        final int prefixLength = prefix.length();

        Iterator<S3ObjectSummary> allSummaries = Iterators.concat(new AbstractIterator<Iterator<S3ObjectSummary>>() {
            String marker = null;
            ObjectListing response;
            protected Iterator<S3ObjectSummary> computeNext() {
                if (response == null || response.isTruncated()) {
                    response = _s3.listObjects(new ListObjectsRequest()
                            .withBucketName(_bucket)
                            .withPrefix(prefix)
                            .withDelimiter("/")
                            .withMarker(marker)
                            .withMaxKeys(1000));
                    marker = response.getNextMarker();
                    return response.getObjectSummaries().iterator();
                }
                return endOfData();
            }
        });

        // Sometimes the prefix itself can come back as a result.  Filter that entry out.
        return Iterators.filter(allSummaries, new Predicate<S3ObjectSummary>() {
            @Override
            public boolean apply(S3ObjectSummary summary) {
                return summary.getKey().length() > prefixLength;
            }
        });
    }
}
