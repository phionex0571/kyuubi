/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kyuubi.session

import scala.collection.JavaConverters._

import org.apache.hive.service.rpc.thrift.TProtocolVersion

import org.apache.kyuubi.client.util.BatchUtils._
import org.apache.kyuubi.config.{KyuubiConf, KyuubiReservedKeys}
import org.apache.kyuubi.engine.KyuubiApplicationManager
import org.apache.kyuubi.engine.spark.SparkProcessBuilder
import org.apache.kyuubi.events.{EventBus, KyuubiSessionEvent}
import org.apache.kyuubi.operation.OperationState
import org.apache.kyuubi.server.metadata.api.Metadata
import org.apache.kyuubi.session.SessionType.SessionType

class KyuubiBatchSession(
    user: String,
    password: String,
    ipAddress: String,
    conf: Map[String, String],
    override val sessionManager: KyuubiSessionManager,
    val sessionConf: KyuubiConf,
    batchType: String,
    batchName: Option[String],
    resource: String,
    className: String,
    batchArgs: Seq[String],
    metadata: Option[Metadata] = None,
    fromRecovery: Boolean)
  extends KyuubiSession(
    TProtocolVersion.HIVE_CLI_SERVICE_PROTOCOL_V1,
    user,
    password,
    ipAddress,
    conf,
    sessionManager) {
  override val sessionType: SessionType = SessionType.BATCH

  override val handle: SessionHandle = {
    val batchId = metadata.map(_.identifier).getOrElse(conf(KYUUBI_BATCH_ID_KEY))
    SessionHandle.fromUUID(batchId)
  }

  override def createTime: Long = metadata.map(_.createTime).getOrElse(super.createTime)

  override def getNoOperationTime: Long = {
    if (batchJobSubmissionOp != null) {
      val batchStatus = batchJobSubmissionOp.getStatus
      if (!OperationState.isTerminal(batchStatus.state)) {
        0L
      } else {
        System.currentTimeMillis() - batchStatus.completed
      }
    } else {
      super.getNoOperationTime
    }
  }

  override val sessionIdleTimeoutThreshold: Long =
    sessionManager.getConf.get(KyuubiConf.BATCH_SESSION_IDLE_TIMEOUT)

  override val normalizedConf: Map[String, String] =
    sessionConf.getBatchConf(batchType) ++ sessionManager.validateBatchConf(conf)

  val optimizedConf: Map[String, String] = {
    val confOverlay = sessionManager.sessionConfAdvisor.getConfOverlay(
      user,
      normalizedConf.asJava)
    if (confOverlay != null) {
      val overlayConf = new KyuubiConf(false)
      confOverlay.asScala.foreach { case (k, v) => overlayConf.set(k, v) }
      normalizedConf ++ overlayConf.getBatchConf(batchType)
    } else {
      warn(s"the server plugin return null value for user: $user, ignore it")
      normalizedConf
    }
  }

  override lazy val name: Option[String] =
    batchName.filterNot(_.trim.isEmpty).orElse(optimizedConf.get(KyuubiConf.SESSION_NAME.key))

  // whether the resource file is from uploading
  private[kyuubi] val isResourceUploaded: Boolean =
    conf.getOrElse(KyuubiReservedKeys.KYUUBI_BATCH_RESOURCE_UPLOADED_KEY, "false").toBoolean

  private[kyuubi] lazy val batchJobSubmissionOp = sessionManager.operationManager
    .newBatchJobSubmissionOperation(
      this,
      batchType,
      name.orNull,
      resource,
      className,
      optimizedConf,
      batchArgs,
      metadata)

  private def waitMetadataRequestsRetryCompletion(): Unit = {
    val batchId = batchJobSubmissionOp.batchId
    sessionManager.getMetadataRequestsRetryRef(batchId).foreach {
      metadataRequestsRetryRef =>
        while (metadataRequestsRetryRef.hasRemainingRequests()) {
          info(s"There are still remaining metadata store requests for batch[$batchId]")
          Thread.sleep(300)
        }
        sessionManager.deRegisterMetadataRequestsRetryRef(batchId)
    }
  }

  private val sessionEvent = KyuubiSessionEvent(this)
  if (fromRecovery) {
    metadata.foreach { m => sessionEvent.engineId = m.engineId }
  }
  EventBus.post(sessionEvent)

  override def getSessionEvent: Option[KyuubiSessionEvent] = {
    Option(sessionEvent)
  }

  override def checkSessionAccessPathURIs(): Unit = {
    KyuubiApplicationManager.checkApplicationAccessPaths(
      batchType,
      optimizedConf,
      sessionManager.getConf)
    if (resource != SparkProcessBuilder.INTERNAL_RESOURCE && !isResourceUploaded) {
      KyuubiApplicationManager.checkApplicationAccessPath(resource, sessionManager.getConf)
    }
  }

  override def open(): Unit = handleSessionException {
    traceMetricsOnOpen()

    lazy val kubernetesInfo: Map[String, String] = {
      val appMgrInfo = batchJobSubmissionOp.builder.appMgrInfo()
      appMgrInfo.kubernetesInfo.context.map { context =>
        Map(KyuubiConf.KUBERNETES_CONTEXT.key -> context)
      }.getOrElse(Map.empty) ++ appMgrInfo.kubernetesInfo.namespace.map { namespace =>
        Map(KyuubiConf.KUBERNETES_NAMESPACE.key -> namespace)
      }.getOrElse(Map.empty)
    }

    (metadata, fromRecovery) match {
      case (Some(initialMetadata), false) =>
        // new batch job created using batch impl v2
        val metadataToUpdate = Metadata(
          identifier = initialMetadata.identifier,
          requestName = name.orNull,
          requestConf = optimizedConf ++ kubernetesInfo, // save the kubernetes info
          clusterManager = batchJobSubmissionOp.builder.clusterManager())
        sessionManager.updateMetadata(metadataToUpdate)
      case (None, _) =>
        // new batch job created using batch impl v1
        val newMetadata = Metadata(
          identifier = handle.identifier.toString,
          sessionType = sessionType,
          realUser = realUser,
          username = user,
          ipAddress = ipAddress,
          kyuubiInstance = connectionUrl,
          state = OperationState.PENDING.toString,
          resource = resource,
          className = className,
          requestName = name.orNull,
          requestConf = optimizedConf ++ kubernetesInfo, // save the kubernetes info
          requestArgs = batchArgs,
          createTime = createTime,
          engineType = batchType,
          clusterManager = batchJobSubmissionOp.builder.clusterManager())

        // there is a chance that operation failed w/ duplicated key error
        sessionManager.insertMetadata(newMetadata)
      case _ =>
    }

    checkSessionAccessPathURIs()

    // create the operation root directory before running batch job submission operation
    super.open()

    runOperation(batchJobSubmissionOp)
    sessionEvent.totalOperations += 1
  }

  private[kyuubi] def onEngineOpened(): Unit = {
    if (sessionEvent.openedTime <= 0) {
      sessionEvent.openedTime = batchJobSubmissionOp.appStartTime
      EventBus.post(sessionEvent)
    }
  }

  override def close(): Unit = {
    super.close()
    batchJobSubmissionOp.close()
    waitMetadataRequestsRetryCompletion()
    sessionEvent.endTime = System.currentTimeMillis()
    EventBus.post(sessionEvent)
    traceMetricsOnClose()
  }
}
