/***********************************************************************
 * Copyright (c) 2013-2018 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.hbase.data

import java.awt.RenderingHints
import java.io.Serializable

import com.typesafe.scalalogging.LazyLogging
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.hadoop.hbase.HBaseConfiguration
import org.apache.hadoop.hbase.client.{Connection, ConnectionFactory, HBaseAdmin}
import org.apache.hadoop.hbase.security.User
import org.apache.hadoop.hbase.security.visibility.VisibilityClient
import org.apache.hadoop.hbase.util.Bytes
import org.apache.hadoop.security.UserGroupInformation
import org.apache.hadoop.security.UserGroupInformation.AuthenticationMethod
import org.geotools.data.DataAccessFactory.Param
import org.geotools.data.{DataStore, DataStoreFactorySpi}
import org.locationtech.geomesa.hbase.data.HBaseDataStoreFactory.HBaseDataStoreConfig
import org.locationtech.geomesa.index.geotools.GeoMesaDataStoreFactory.{GeoMesaDataStoreConfig, GeoMesaDataStoreParams}
import org.locationtech.geomesa.security
import org.locationtech.geomesa.security.{AuthorizationsProvider, SecurityParams}
import org.locationtech.geomesa.utils.audit.{AuditLogger, AuditProvider, AuditWriter, NoOpAuditProvider}
import org.locationtech.geomesa.utils.conf.GeoMesaSystemProperties.SystemProperty
import org.locationtech.geomesa.utils.geotools.GeoMesaParam

import scala.collection.JavaConversions._


class HBaseDataStoreFactory extends DataStoreFactorySpi with LazyLogging {

  import HBaseDataStoreParams._

  // TODO: investigate multiple HBase connections per jvm
  private lazy val globalConnection: Connection = {
    val conf = HBaseConfiguration.create()
    HBaseDataStoreFactory.configureSecurity(conf)
    checkClusterAvailability(conf)
    val ret = ConnectionFactory.createConnection(conf)
    Runtime.getRuntime.addShutdownHook(new Thread() {
      override def run(): Unit = {
        ret.close()
      }
    })
    ret
  }

  // this is a pass-through required of the ancestor interface
  override def createNewDataStore(params: java.util.Map[String, Serializable]): DataStore = createDataStore(params)

  override def createDataStore(params: java.util.Map[String, Serializable]): DataStore = {

    // TODO HBase Connections don't seem to be Serializable...deal with it
    val connection = ConnectionParam.lookupOpt(params).getOrElse(globalConnection)

    val catalog = getCatalog(params)

    val remoteFilters = RemoteFilteringParam.lookupOpt(params).map(_.booleanValue)
      .getOrElse(SystemProperty("geomesa.hbase.remote.filtering", "true").get.toBoolean)
    logger.debug(s"Using ${if (remoteFilters) "remote" else "local" } filtering")

    val generateStats = GenerateStatsParam.lookup(params)
    val audit = if (AuditQueriesParam.lookup(params)) {
      Some(AuditLogger, Option(AuditProvider.Loader.load(params)).getOrElse(NoOpAuditProvider), "hbase")
    } else {
      None
    }
    val queryThreads = QueryThreadsParam.lookup(params)
    val queryTimeout = QueryTimeoutParam.lookupOpt(params).map(_.toMillis)
    val maxRangesPerExtendedScan = MaxRangesPerExtendedScanParam.lookup(params)
    val looseBBox = LooseBBoxParam.lookup(params)
    val caching = CachingParam.lookup(params)
    val authsProvider = if (!EnableSecurityParam.lookup(params)) { None } else {
      Some(HBaseDataStoreFactory.buildAuthsProvider(connection, params))
    }
    val coprocessorUrl = CoprocessorUrlParam.lookupOpt(params)

    val ns = NamespaceParam.lookupOpt(params)

    val config = HBaseDataStoreConfig(catalog, remoteFilters, generateStats, audit, queryThreads, queryTimeout,
      maxRangesPerExtendedScan, looseBBox, caching, authsProvider, coprocessorUrl, ns)
    buildDataStore(connection, config)
  }

  // overridden by BigtableFactory
  protected def getCatalog(params: java.util.Map[String, Serializable]): String = HBaseCatalogParam.lookup(params)

  // overridden by BigtableFactory
  protected def buildDataStore(connection: Connection, config: HBaseDataStoreConfig): HBaseDataStore =
    new HBaseDataStore(connection, config)

  // overridden by BigtableFactory
  protected def checkClusterAvailability(conf: Configuration): Unit = {
    logger.debug("Checking configuration availability.")
    HBaseAdmin.checkHBaseAvailable(conf)
  }

  override def getDisplayName: String = HBaseDataStoreFactory.DisplayName

  override def getDescription: String = HBaseDataStoreFactory.Description

  override def getParametersInfo: Array[Param] =
    Array(
      HBaseCatalogParam,
      RemoteFilteringParam,
      QueryThreadsParam,
      QueryTimeoutParam,
      CoprocessorUrlParam,
      GenerateStatsParam,
      AuditQueriesParam,
      LooseBBoxParam,
      CachingParam,
      EnableSecurityParam,
      AuthsParam,
      ForceEmptyAuthsParam,
      NamespaceParam
    )

  override def canProcess(params: java.util.Map[String,Serializable]): Boolean =
    HBaseDataStoreFactory.canProcess(params)

  override def isAvailable = true

  override def getImplementationHints: java.util.Map[RenderingHints.Key, _] = null
}

object HBaseDataStoreFactory extends LazyLogging {

  import HBaseDataStoreParams._

  val DisplayName = "HBase (GeoMesa)"
  val Description = "Apache HBase\u2122 distributed key/value store"

  val HBaseGeoMesaPrincipal = "hbase.geomesa.principal"
  val HBaseGeoMesaKeyTab    = "hbase.geomesa.keytab"

  private [geomesa] val BigTableParamCheck = "google.bigtable.instance.id"

  case class HBaseDataStoreConfig(catalog: String,
                                  remoteFilter: Boolean,
                                  generateStats: Boolean,
                                  audit: Option[(AuditWriter, AuditProvider, String)],
                                  queryThreads: Int,
                                  queryTimeout: Option[Long],
                                  maxRangesPerExtendedScan: Int,
                                  looseBBox: Boolean,
                                  caching: Boolean,
                                  authProvider: Option[AuthorizationsProvider],
                                  coprocessorUrl: Option[Path],
                                  namespace: Option[String]) extends GeoMesaDataStoreConfig

  // check that the hbase-site.xml does not have bigtable keys
  def canProcess(params: java.util.Map[java.lang.String,Serializable]): Boolean = {
    HBaseCatalogParam.exists(params) &&
      Option(HBaseConfiguration.create().get(BigTableParamCheck)).forall(_.trim.isEmpty)
  }

  def buildAuthsProvider(connection: Connection, params: java.util.Map[String, Serializable]): AuthorizationsProvider = {
    val forceEmptyOpt: Option[java.lang.Boolean] = ForceEmptyAuthsParam.lookupOpt(params)
    val forceEmptyAuths = forceEmptyOpt.getOrElse(java.lang.Boolean.FALSE).asInstanceOf[Boolean]

    if (!VisibilityClient.isCellVisibilityEnabled(connection)) {
      throw new IllegalArgumentException("HBase cell visibility is not enabled on cluster")
    }

    // master auths is the superset of auths this connector/user can support
    val userName = User.getCurrent.getName
    val masterAuths = VisibilityClient.getAuths(connection, userName).getAuthList.map(a => Bytes.toString(a.toByteArray))

    // get the auth params passed in as a comma-delimited string
    val configuredAuths = AuthsParam.lookupOpt(params).getOrElse("").split(",").filter(s => !s.isEmpty)

    // verify that the configured auths are valid for the connector we are using (fail-fast)
    val invalidAuths = configuredAuths.filterNot(masterAuths.contains)
    if (invalidAuths.nonEmpty) {
      throw new IllegalArgumentException(s"The authorizations '${invalidAuths.mkString(",")}' " +
        "are not valid for the HBase user and connection being used")
    }

    // if the caller provided any non-null string for authorizations, use it;
    // otherwise, grab all authorizations to which the Accumulo user is entitled
    if (configuredAuths.length != 0 && forceEmptyAuths) {
      throw new IllegalArgumentException("Forcing empty auths is checked, but explicit auths are provided")
    }
    val auths: List[String] =
      if (forceEmptyAuths || configuredAuths.length > 0) configuredAuths.toList
      else masterAuths.toList

    security.getAuthorizationsProvider(params, auths)
  }

  def configureSecurity(conf: Configuration): Unit = {
    val auth = conf.get("hbase.security.authentication")
    auth match {
      case "kerberos" =>
        val authMethod: AuthenticationMethod = org.apache.hadoop.security.SecurityUtil.getAuthenticationMethod(conf)
        logger.debug(s"Auth method: $authMethod")

        if (authMethod != AuthenticationMethod.KERBEROS || authMethod != AuthenticationMethod.KERBEROS_SSL) {
          logger.warn(s"HBase is configured to used Kerberos.  The Hadoop configuration is missing or not configured to use Kerberos.")
        }

        UserGroupInformation.setConfiguration(conf)

        logger.debug(s"Is Hadoop security enabled: ${UserGroupInformation.isSecurityEnabled}")
        logger.debug(s"Using Kerberos with principal ${conf.get(HBaseGeoMesaPrincipal)} and file ${conf.get(HBaseGeoMesaKeyTab)}")
        UserGroupInformation.loginUserFromKeytab(conf.get(HBaseGeoMesaPrincipal), conf.get(HBaseGeoMesaKeyTab))

      case _ =>
        logger.debug(s"Hadoop is not configured to use Kerberos.  The value of the setting 'hbase.security.authentication' $auth.")
    }
  }

}

object HBaseDataStoreParams extends GeoMesaDataStoreParams with SecurityParams {
  val HBaseCatalogParam             = new GeoMesaParam[String]("hbase.catalog", "Catalog table name", optional = false, deprecatedKeys = Seq("bigtable.table.name"))
  val ConnectionParam               = new GeoMesaParam[Connection]("hbase.connection", "Connection", deprecatedKeys = Seq("connection"))
  val CoprocessorUrlParam           = new GeoMesaParam[Path]("hbase.coprocessor.url", "Coprocessor Url", deprecatedKeys = Seq("coprocessor.url"))
  val RemoteFilteringParam          = new GeoMesaParam[java.lang.Boolean]("hbase.remote.filtering", "Remote filtering", default = true, deprecatedKeys = Seq("remote.filtering"))
  val MaxRangesPerExtendedScanParam = new GeoMesaParam[java.lang.Integer]("hbase.ranges.max-per-extended-scan", "Max Ranges per Extended Scan", default = 100, deprecatedKeys = Seq("max.ranges.per.extended.scan"))
  val EnableSecurityParam           = new GeoMesaParam[java.lang.Boolean]("hbase.security.enabled", "Enable HBase Security (Visibilities)", default = false, deprecatedKeys = Seq("security.enabled"))
}
