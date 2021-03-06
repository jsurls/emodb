package com.bazaarvoice.emodb.blob.client;

import com.bazaarvoice.emodb.common.dropwizard.discovery.ConfiguredFixedHostDiscoverySource;
import com.bazaarvoice.emodb.common.dropwizard.discovery.ConfiguredPayload;
import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.Map;

/**
 * A SOA (Ostrich) helper class that can be used to configure a {@link com.bazaarvoice.ostrich.ServicePool}
 * with a fixed, hard-coded set of hosts, useful for testing and for cross-data center API calls where
 * the client and EmoDB servers aren't in the same data center and don't have access to the same ZooKeeper
 * ensemble.
 *
 * @see ConfiguredFixedHostDiscoverySource
 */
public class BlobStoreFixedHostDiscoverySource extends ConfiguredFixedHostDiscoverySource {

    public BlobStoreFixedHostDiscoverySource() {
        super();
    }

    public BlobStoreFixedHostDiscoverySource(String... hosts) {
        super(hosts);
    }

    @JsonCreator
    public BlobStoreFixedHostDiscoverySource(Map<String, ConfiguredPayload> endPoints) {
        super(endPoints);
    }

    @Override
    protected String getBaseServiceName() {
        return BlobStoreClient.BASE_SERVICE_NAME;
    }

    @Override
    protected String getServicePath() {
        return BlobStoreClient.SERVICE_PATH;
    }
}
