Using the HBase Data Store Programmatically
===========================================

Creating a Data Store
---------------------

An instance of an HBase data store can be obtained through the normal GeoTools discovery methods,
assuming that the GeoMesa code is on the classpath. The HBase data store also requires that an
``hbase-site.xml`` be located on the classpath; the connection parameters for the HBase data store,
including ``hbase.zookeeper.quorum`` and ``hbase.zookeeper.property.clientPort``, are obtained from this file.

.. code-block:: java

    Map<String, Serializable> parameters = new HashMap<>();
    parameters.put("hbase.catalog", "geomesa");
    org.geotools.data.DataStore dataStore =
        org.geotools.data.DataStoreFinder.getDataStore(parameters);

.. _hbase_parameters:

HBase Data Store Parameters
---------------------------

The data store takes several parameters (required parameters are marked with ``*``):

====================================== ======= ====================================================================================
Parameter                              Type    Description
====================================== ======= ====================================================================================
``hbase.catalog *``                    String  The name of the GeoMesa catalog table (previously ``bigtable.table.name``)
``hbase.coprocessor.url``              String  Path to the GeoMesa jar containing coprocessors, for auto registration
``hbase.remote.filtering``             Boolean Can be used to disable remote filtering and coprocessors, for environments
                                               where custom code can't be installed
``hbase.security.enabled``             Boolean Enable HBase security (visibilities)
``geomesa.security.auths``             String  Comma-delimited superset of authorizations that will be used for queries
``geomesa.security.force-empty-auths`` Boolean Forces authorizations to be empty
``geomesa.query.audit``                Boolean Audit queries being run. Queries will be written to a log file
``geomesa.query.timeout``              String  The max time a query will be allowed to run before being killed. The
                                               timeout is specified as a duration, e.g. ``1 minute`` or ``60 seconds``
``geomesa.query.threads``              Integer The number of threads to use per query
``geomesa.query.loose-bounding-box``   Boolean Use loose bounding boxes - queries will be faster but may return extraneous results
``hbase.ranges.max-per-extended-scan`` Integer The max number of ranges used for each extended scan
``geomesa.stats.generate``             Boolean Toggle collection of statistics (currently not implemented)
``geomesa.query.caching``              Boolean Toggle caching of results
====================================== ======= ====================================================================================

More information on using GeoTools can be found in the `GeoTools user guide
<http://docs.geotools.org/stable/userguide/>`__.
