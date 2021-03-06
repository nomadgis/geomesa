/***********************************************************************
 * Copyright (c) 2013-2018 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.stream.datastore

import java.awt.RenderingHints
import java.util.concurrent.{CopyOnWriteArrayList, Executors, TimeUnit}
import java.util.logging.Level
import java.{util => ju}

import com.github.benmanes.caffeine.cache.{Cache, Caffeine, RemovalCause, RemovalListener}
import com.google.common.collect.Lists
import com.typesafe.config.ConfigFactory
import com.vividsolutions.jts.geom.Envelope
import org.geotools.data.DataAccessFactory.Param
import org.geotools.data._
import org.geotools.data.simple.{DelegateSimpleFeatureReader, SimpleFeatureReader}
import org.geotools.data.store._
import org.geotools.feature.collection.DelegateSimpleFeatureIterator
import org.geotools.filter.FidFilterImpl
import org.geotools.geometry.jts.ReferencedEnvelope
import org.geotools.referencing.crs.DefaultGeographicCRS
import org.locationtech.geomesa.filter.index.SpatialIndexSupport
import org.locationtech.geomesa.stream.SimpleFeatureStreamSource
import org.locationtech.geomesa.utils.collection.SelfClosingIterator
import org.locationtech.geomesa.utils.geotools.Conversions._
import org.locationtech.geomesa.utils.geotools.{FR, GeoMesaParam}
import org.locationtech.geomesa.utils.index.{SpatialIndex, SynchronizedQuadtree}
import org.opengis.feature.`type`.Name
import org.opengis.feature.simple.{SimpleFeature, SimpleFeatureType}
import org.opengis.filter.Filter

import scala.collection.JavaConversions._

case class FeatureHolder(sf: SimpleFeature, env: Envelope) {
  override def hashCode(): Int = sf.hashCode()

  override def equals(obj: scala.Any): Boolean = obj match {
    case other: FeatureHolder => sf.equals(other.sf)
    case _ => false
  }
}

class StreamDataStore(source: SimpleFeatureStreamSource, timeout: Int) extends ContentDataStore {
  
  val sft = source.sft
  source.init()
  val qt = new SynchronizedQuadtree[SimpleFeature]

  val cb =
    Caffeine
      .newBuilder()
      .expireAfterWrite(timeout, TimeUnit.SECONDS)
      .removalListener(
        new RemovalListener[String, FeatureHolder] {
          override def onRemoval(k: String, v: FeatureHolder, removalCause: RemovalCause): Unit = {
            qt.remove(v.env, v.sf)
          }
        }
      )

  val features = cb.build[String, FeatureHolder]()

  val listeners = new CopyOnWriteArrayList[StreamListener]()

  private val executor = Executors.newSingleThreadExecutor()
  executor.submit(
    new Runnable {
      override def run(): Unit = {
        while(true) {
          try {
            val sf = source.next
            if(sf != null) {
              val env = sf.geometry.getEnvelopeInternal
              qt.insert(env, sf)
              features.put(sf.getID, FeatureHolder(sf, env))
              listeners.foreach { l =>
                try {
                  l.onNext(sf)
                } catch {
                  case t: Throwable => getLogger.log(Level.WARNING, "Unable to notify listener", t)
                }
              }
            }
          } catch {
            case t: Throwable =>
            // swallow
          }
        }
      }
    }
  )

  override def createFeatureSource(entry: ContentEntry): ContentFeatureSource =
    new StreamFeatureStore(entry, null, features, qt, sft)

  def registerListener(listener: StreamListener): Unit = listeners.add(listener)

  override def createTypeNames(): ju.List[Name] = Lists.newArrayList(sft.getName)

  def close(): Unit = {
    try {
      executor.shutdown()
    } catch {
      case t: Throwable => // swallow
    }
  }
}

class StreamFeatureStore(entry: ContentEntry,
                         query: Query,
                         features: Cache[String, FeatureHolder],
                         val spatialIndex: SpatialIndex[SimpleFeature],
                         val sft: SimpleFeatureType)
  extends ContentFeatureStore(entry, query) with SpatialIndexSupport {

  override def canFilter: Boolean = true

  override def getBoundsInternal(query: Query): ReferencedEnvelope =
    ReferencedEnvelope.create(new Envelope(-180, 180, -90, 90), DefaultGeographicCRS.WGS84)

  override def buildFeatureType(): SimpleFeatureType = sft

  override def getCountInternal(query: Query): Int = SelfClosingIterator(getReaderInternal(query)).length

  override def getReaderInternal(query: Query): FR = reader(this.query(query.getFilter))

  override def allFeatures(): Iterator[SimpleFeature] = features.asMap().valuesIterator.map(_.sf)

  override def query(f: Filter): Iterator[SimpleFeature] =
    f match {
      case id: FidFilterImpl => id.getIDs.flatMap(id => Option(features.getIfPresent(id.toString)).map(_.sf)).iterator
      case _                 => super.query(f)
    }

  override def getWriterInternal(query: Query, flags: Int) = throw new IllegalArgumentException("Not allowed")

  protected def reader(iter: Iterator[SimpleFeature]): SimpleFeatureReader =
    new DelegateSimpleFeatureReader(sft, new DelegateSimpleFeatureIterator(iter))
}

object StreamDataStoreParams {
  val StreamDatastoreConfig = new GeoMesaParam[String]("geomesa.stream.datastore.config", "", optional = false)
  val CacheTimeout = new GeoMesaParam[Integer]("geomesa.stream.datastore.cache.timeout", "", optional = false, default = 10)
}

class StreamDataStoreFactory extends DataStoreFactorySpi {

  import StreamDataStoreParams._

  override def createDataStore(params: ju.Map[String, java.io.Serializable]): DataStore = {
    val confString = StreamDatastoreConfig.lookup(params)
    val timeout = CacheTimeout.lookupOpt(params).map(_.intValue).getOrElse(10)
    val conf = ConfigFactory.parseString(confString)
    val source = SimpleFeatureStreamSource.buildSource(conf)
    new StreamDataStore(source, timeout)
  }

  override def createNewDataStore(params: ju.Map[String, java.io.Serializable]): DataStore = createDataStore(params)
  override def getDescription: String = "SimpleFeature Stream Source"
  override def getParametersInfo: Array[Param] = Array(StreamDatastoreConfig, CacheTimeout)
  override def getDisplayName: String = "SimpleFeature Stream Source"
  override def canProcess(params: ju.Map[String, java.io.Serializable]): Boolean =
    StreamDatastoreConfig.exists(params)

  override def isAvailable: Boolean = true
  override def getImplementationHints: ju.Map[RenderingHints.Key, _] = null
}

trait StreamListener {
  def onNext(sf: SimpleFeature): Unit
}

object StreamListener {
  def apply(f: Filter, fn: SimpleFeature => Unit) =
    new StreamListener {
      override def onNext(sf: SimpleFeature): Unit = if(f.evaluate(sf)) fn(sf)
    }

  def apply(fn: SimpleFeature => Unit) =
    new StreamListener {
      override def onNext(sf: SimpleFeature): Unit = fn(sf)
    }
}

