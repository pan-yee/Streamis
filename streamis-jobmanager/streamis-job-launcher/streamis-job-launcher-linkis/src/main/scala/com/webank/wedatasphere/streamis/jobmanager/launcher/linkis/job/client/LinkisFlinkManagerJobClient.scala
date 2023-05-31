package com.webank.wedatasphere.streamis.jobmanager.launcher.linkis.job.client

import com.webank.wedatasphere.streamis.jobmanager.launcher.enums.JobClientType
import com.webank.wedatasphere.streamis.jobmanager.launcher.job.conf.JobConf
import com.webank.wedatasphere.streamis.jobmanager.launcher.job.constants.JobConstants
import com.webank.wedatasphere.streamis.jobmanager.launcher.job.errorcode.JobLaunchErrorCode
import com.webank.wedatasphere.streamis.jobmanager.launcher.job.{FlinkManagerClient, JobClient, JobInfo}
import com.webank.wedatasphere.streamis.jobmanager.launcher.job.manager.JobStateManager
import com.webank.wedatasphere.streamis.jobmanager.launcher.job.state.JobStateInfo
import com.webank.wedatasphere.streamis.jobmanager.launcher.linkis.conf.JobLauncherConfiguration
import com.webank.wedatasphere.streamis.jobmanager.launcher.linkis.exception.{FlinkECHandshakeErrorException, FlinkJobKillECErrorException, FlinkJobParamErrorException, FlinkJobStateFetchException, FlinkSavePointException}
import com.webank.wedatasphere.streamis.jobmanager.launcher.linkis.job.action.{FlinkKillAction, FlinkSaveAction, FlinkStatusAction}
import com.webank.wedatasphere.streamis.jobmanager.launcher.linkis.job.jobInfo.{EngineConnJobInfo, LinkisJobInfo}
import com.webank.wedatasphere.streamis.jobmanager.launcher.linkis.job.state.FlinkSavepoint
import org.apache.commons.lang3.StringUtils
import org.apache.linkis.common.exception.LinkisRetryException
import org.apache.linkis.common.utils.{JsonUtils, RetryHandler, Utils}
import org.apache.linkis.computation.client.once.OnceJob
import org.apache.linkis.computation.client.once.result.EngineConnOperateResult
import org.apache.linkis.computation.client.once.simple.SimpleOnceJob
import org.apache.linkis.governance.common.constant.ec.ECConstants
import org.apache.linkis.protocol.utils.TaskUtils
import org.apache.linkis.ujes.client.exception.UJESJobException

import java.util
import java.util.concurrent.TimeUnit
import scala.collection.JavaConverters.mapAsScalaMapConverter
import scala.tools.scalap.scalax.util.StringUtil

class LinkisFlinkManagerJobClient(onceJob: OnceJob, jobInfo: JobInfo, stateManager: JobStateManager) extends EngineConnJobClient(onceJob, jobInfo, stateManager) {

  private lazy val linkisFlinkManagerClient: LinkisFlinkManagerClient = LinkisFlinkManagerClient.getInstance()

  override def init(): Unit = {
    super.init()
    // 初始化linkisclient，调用managerexecutor进行操作的client

    logger.info("LinkisFlinkManagerJobClient inited.")
  }


  private def isDetachJob(info: JobInfo): Boolean = {
    JobClientType.OTHER.toJobClientType(jobInfo.getClientType.toLowerCase()) match {
      case JobClientType.ATTACH =>
        false
      case JobClientType.DETACH =>
        true
      case JobClientType.DETACH_STANDALONE =>
        // TODO  check
       true
      case _ =>
        throw new FlinkJobParamErrorException(s"Job with manager mode : ${jobInfo.getClientType} cannot be submited.", null)
    }
  }

  override def getJobInfo(refresh: Boolean): JobInfo = {
    onceJob match {
      case simpleOnceJob: SimpleOnceJob =>
        if (StringUtils.isNotBlank(jobInfo.getStatus) && JobConf.isCompleted(JobConf.linkisStatusToStreamisStatus(jobInfo.getStatus))) {
          jobInfo.setStatus(simpleOnceJob.getStatus)
          logger.info(s"Job : ${simpleOnceJob.getId} is completed, no need to get status from linkis.")
        } else if (refresh && isDetachJob(jobInfo)) {
          jobInfo match {
            case engineConnJobInfo: EngineConnJobInfo =>
              jobInfo.setStatus(getJobStatusWithRetry(engineConnJobInfo.getApplicationId))
            case _ =>
              throw new FlinkJobParamErrorException(s"Invalid jobInfo : ${jobInfo} , cannot get status.", null)
          }
        } else {
          return super.getJobInfo(refresh)
        }
    }
    jobInfo
  }

  override def stop(snapshot: Boolean): JobStateInfo = {
    if (isDetachJob(jobInfo)) {
      jobInfo match {
        case engineConnJobInfo: EngineConnJobInfo =>
          val appId = engineConnJobInfo.getApplicationId
          return stopApp(appId, snapshot)
        case _ =>
          throw new FlinkJobParamErrorException(s"Invalid jobInfo : ${jobInfo} , cannot stop.", null)
      }
    } else {
      return super.stop(snapshot)
    }
  }

  def getJobStatusWithRetry(appId: String): String = {
    val retryHandler = new RetryHandler {}
    retryHandler.setRetryNum(3)
    retryHandler.setRetryMaxPeriod(5000)
    retryHandler.setRetryPeriod(1000)
    retryHandler.addRetryException(classOf[UJESJobException])
    retryHandler.addRetryException(classOf[LinkisRetryException])
    retryHandler.addRetryException(classOf[FlinkJobStateFetchException])
    retryHandler.retry(
      {
    val statusAction = new FlinkStatusAction(appId, null)
    val rs = linkisFlinkManagerClient.executeAction(statusAction)
    rs match {
      case engineConnOperateResult: EngineConnOperateResult =>
        if (engineConnOperateResult.getIsError()) {
          throw new FlinkJobStateFetchException(errorMsg = s"Get status error. Because : ${engineConnOperateResult.getErrorMsg()}", t = null)
        }
        val rsMap = engineConnOperateResult.getResult
        val status = rsMap.getOrDefault(ECConstants.NODE_STATUS_KEY, null)
            logger.info(s"AppId : ${appId} got status : ${status}")
        if (null != status) {
          return status.toString
        } else {
          val json = JsonUtils.jackson.writeValueAsString(rsMap)
          throw new FlinkJobStateFetchException(errorMsg = s"Get invalid status. Result map : ${json}", t = null)
        }
      case _ =>
        val json = JsonUtils.jackson.writeValueAsString(rs)
        throw new FlinkJobStateFetchException(errorMsg = s"Get invalid result. Response json : ${json}", t = null)
    }
      },
      "Retry-Get-Status")
  }

  def stopApp(appId: String, snapshot: Boolean): JobStateInfo = {
    val jobStateInfo = new JobStateInfo()
    if (snapshot) {
      val savepointURI = this.stateManager.getJobStateDir(classOf[FlinkSavepoint], jobInfo.getName)
      val flinkSavepoint = doSavePoint(appId, null, savepointURI.toString, JobLauncherConfiguration.FLINK_TRIGGER_SAVEPOINT_MODE.getValue)
      jobStateInfo.setLocation(flinkSavepoint.getLocation.toString)
      jobStateInfo.setTimestamp(flinkSavepoint.getTimestamp)
    }
    val stopAction = new FlinkKillAction(appId, null)
    val rs = linkisFlinkManagerClient.executeAction(stopAction)
    rs match {
      case engineConnOperateResult: EngineConnOperateResult =>
        if (engineConnOperateResult.getIsError()) {
          throw new FlinkJobStateFetchException(errorMsg = s"Get status error. Because : ${engineConnOperateResult.getErrorMsg()}", t = null)
        }
      case _ =>
        val json = JsonUtils.jackson.writeValueAsString(rs)
        throw new FlinkJobStateFetchException(errorMsg = s"Get invalid result. Response json : ${json}", t = null)
    }
    if (StringUtils.isBlank(jobStateInfo.getLocation)) {
      jobStateInfo.setLocation("No location")
    }
    jobStateInfo
  }

  override def triggerSavepoint(savePointDir: String, mode: String): FlinkSavepoint = {
    if (isDetachJob(jobInfo)) {
      var appId: String = null
      jobInfo match {
        case engineConnJobInfo: EngineConnJobInfo =>
          appId = engineConnJobInfo.getApplicationId
      }
      doSavePoint(appId, null, savePointDir, mode)
    } else {
      super.triggerSavepoint(savePointDir, mode)
    }
  }

  def doSavePoint(appId: String, msg: String, savePointDir: String, mode: String): FlinkSavepoint = {

    val savepointAction = new FlinkSaveAction(appId, msg)
    savepointAction.setSavepointPath(savePointDir)
    savepointAction.setMode(mode)
    val rs = linkisFlinkManagerClient.executeAction(savepointAction)
    rs match {
      case engineConnOperateResult: EngineConnOperateResult =>
        if (engineConnOperateResult.getIsError()) {
          throw new FlinkJobKillECErrorException(s"Do savepoint error. Because : ${engineConnOperateResult.getErrorMsg()}", null)
        }
        val writePath = engineConnOperateResult.getAs[String](JobConstants.RESULT_SAVEPOINT_PATH_KEY)
        if (StringUtils.isBlank(writePath)) {
          val msg = s"Do savepoint error got null write path. Errormsg : ${engineConnOperateResult.getErrorMsg()} "
          throw new FlinkSavePointException(errorMsg = msg, t = null)
        }
        new FlinkSavepoint(writePath)
      case _ =>
        val rsMsg = JsonUtils.jackson.writeValueAsString(rs)
        val msg = s"Get status error. Result : ${rsMsg}"
        throw new FlinkSavePointException(errorMsg = msg, t = null)
    }
  }

  override def handshake(): Unit = {
    val engineConnJobInfo = jobInfo.asInstanceOf[EngineConnJobInfo]
    val handshakeAction = new FlinkStatusAction(engineConnJobInfo.getApplicationId, "handshake")
    handshakeAction.setECInstance(engineConnJobInfo.getEcInstance())
    handshakeAction.setExeuteUser(engineConnJobInfo.getUser)
    val rs = linkisFlinkManagerClient.doExecution(handshakeAction.build())
    rs match {
      case engineConnOperateResult: EngineConnOperateResult =>
        if (engineConnOperateResult.getIsError()) {
          throw new FlinkECHandshakeErrorException(s"Do hankshake error. Because : ${engineConnOperateResult.getErrorMsg()}", null)
        } else {
          val status = engineConnOperateResult.getResult.getOrDefault(ECConstants.NODE_STATUS_KEY, null)
          logger.info(s"Handshake success. Status : ${status}")
        }
      case _ =>
        val rsMsg = JsonUtils.jackson.writeValueAsString(rs)
        val msg = s"Get status error. Result : ${rsMsg}"
        throw new FlinkECHandshakeErrorException(errorMsg = msg, t = null)
    }
  }
}
