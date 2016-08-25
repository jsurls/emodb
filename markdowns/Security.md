Security
========

History
-------

The original version of EmoDB did not provide any form of security; any valid API request would be honored
without need for any credentials (with few exceptions concerning the most destructive operations).  Since EmoDB is a
shared service this lead to a situation where any of the following was possible:

1. One team inadvertently corrupts data owned by another team.
2. A team accidentally runs a script intended for one universe (e.g.; QA) against another universe (e.g.; PROD).
3. Accountability for such operations is lost if the self-reported audits are non-specific to origin.

API Keys
--------

To resolve these issues EmoDB has introduced API keys.  Each individual or team can be granted an API key which can be
narrowly permitted to perform only the actions required by the system.  This includes restricting access to specific
resources (e.g.; permitting access only to tables matching "my_team_prefix:*") as well as restricting access levels to
those resources (e.g.; read-only vs. read-write).


How to get an API key
---------------------

For API Key administration, see [Security Management] (https://github.com/bazaarvoice/emodb/blob/master/markdowns/SecurityManagement.md)


Using your API key
---------------------

### Direct access

If you are making API calls directly against the EmoDB API, such as through `curl`, then you can pass your API key
in either of the following ways:

1. Create a `X-BV-API-Key` header with your key's value:

```
$ curl -s -H "X-BV-API-Key: <your_api_key_>" http://localhost:8080/sor/1/review:testcustomer/demo1"
```

2. Add a `APIKey` query parameter to your call:

```
$ curl -s http://localhost:8080/sor/1/review:testcustomer/demo1?APIKey=<your_api_key>"
```

### Java client

If you are using the Java client then migrating your code using API keys requires only a minor change to your existing
code.  For example, before API keys you could create a `DataStore` client using a `ServicePoolBuilder` similar to the
following:

```java
String emodbHost = "localhost:8080";  // Adjust to point to the EmoDB server.
DataStore dataStore = ServicePoolBuilder.create(DataStore.class)
        .withHostDiscoverySource(new DataStoreFixedHostDiscoverySource(emodbHost))
        .withServiceFactory(DataStoreClientFactory.forCluster("local_default"))
        .buildProxy(new ExponentialBackoffRetry(5, 50, 1000, TimeUnit.MILLISECONDS));
```

For the simple case where your entire application uses a single API key add a `usingCredentials()` call
to the client factory.  For example the previous example would be updated to the following:

```java
String emodbHost = "localhost:8080";  // Adjust to point to the EmoDB server.
String apiKey = "<your_api_key>";
DataStore dataStore = ServicePoolBuilder.create(DataStore.class)
        .withHostDiscoverySource(new DataStoreFixedHostDiscoverySource(emodbHost))
        .withServiceFactory(DataStoreClientFactory.forCluster("local_default").usingCredentials(apiKey))
        .buildProxy(new ExponentialBackoffRetry(5, 50, 1000, TimeUnit.MILLISECONDS));
```

If you application uses multiple API keys then it would be inefficient to create a separate service pool for each data
store.  Instead you can use a `DataStoreAuthenticator` to re-use a single data store pool for multiple API keys.

To do this change `DataStore.class` to `AuthDataStore.class` and wrap it using a `DataStoreAuthenticator`.
This time the previous example would be updated to the following:

```java
String emodbHost = "localhost:8080";  // Adjust to point to the EmoDB server.
// Use AuthDataStore
AuthDataStore authDataStore = ServicePoolBuilder.create(AuthDataStore.class)
        .withHostDiscoverySource(new DataStoreFixedHostDiscoverySource(emodbHost))
        .withServiceFactory(DataStoreClientFactory.forCluster("local_default"))
        .buildProxy(new ExponentialBackoffRetry(5, 50, 1000, TimeUnit.MILLISECONDS));

// Create a DataStoreAuthenticator from the AuthDataStore service pool
DataStoreAuthenticator dataStoreAuthenticator = DataStoreAuthenticator.proxied(authDataStore)

String apiKey = getApiKey();
// Convert back to a DataStore view
DataStore dataStore = dataStoreAuthenticator.usingCredentials(apiKey);
```

From this point the `DataStore` API is unchanged and all calls to the data store will automatically include your API key.

Although not presented here `BlobStore`, `DataBus`, `QueueService`, and `DedupQueueService` all have a corresponding
`usingCredentials()` method, `Auth` and `Authenticator` classes and can be updated following the same pattern.


Managing API Keys
-----------------

Please see [Key management documentation] (https://github.com/bazaarvoice/emodb/blob/master/markdowns/SecurityManagement.md)