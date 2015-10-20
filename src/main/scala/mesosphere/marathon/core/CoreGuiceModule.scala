package mesosphere.marathon.core

import com.google.inject.{ AbstractModule, Provides, Scopes, Singleton }
import mesosphere.marathon.core.appinfo.{ AppInfoModule, AppInfoService }
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.launcher.OfferProcessor
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.leadership.{ LeadershipCoordinator, LeadershipModule }
import mesosphere.marathon.core.plugin.PluginManager
import mesosphere.marathon.core.task.bus.{ TaskStatusEmitter, TaskStatusObservables }
import mesosphere.marathon.core.task.tracker.impl.TaskStatusUpdateProcessorImpl
import mesosphere.marathon.core.task.tracker.impl.steps.{
  AcknowledgeTaskUpdateStepImpl,
  ContinueOnErrorStep,
  NotifyHealthCheckManagerStepImpl,
  NotifyLaunchQueueStepImpl,
  NotifyRateLimiterStepImpl,
  PostToEventStreamStepImpl,
  ScaleAppUpdateStepImpl,
  TaskStatusEmitterPublishStepImpl,
  UpdateTaskTrackerStepImpl
}
import mesosphere.marathon.core.task.tracker.{ TaskStatusUpdateProcessor, TaskStatusUpdateStep, TaskTrackerModule }
import mesosphere.marathon.plugin.auth.{ Authenticator, Authorizer }

/**
  * Provides the glue between guice and the core modules.
  */
class CoreGuiceModule extends AbstractModule {

  // Export classes used outside of core to guice
  @Provides @Singleton
  def leadershipModule(coreModule: CoreModule): LeadershipModule = coreModule.leadershipModule

  @Provides @Singleton
  def leadershipCoordinator(
    leadershipModule: LeadershipModule,
    makeSureToInitializeThisBeforeCreatingCoordinator: TaskStatusUpdateProcessor): LeadershipCoordinator =
    leadershipModule.coordinator()

  @Provides @Singleton
  def offerProcessor(coreModule: CoreModule): OfferProcessor = coreModule.launcherModule.offerProcessor

  @Provides @Singleton
  def taskStatusEmitter(coreModule: CoreModule): TaskStatusEmitter = coreModule.taskBusModule.taskStatusEmitter

  @Provides @Singleton
  def taskStatusObservable(coreModule: CoreModule): TaskStatusObservables =
    coreModule.taskBusModule.taskStatusObservables

  @Provides @Singleton
  def taskTrackerModule(coreModule: CoreModule): TaskTrackerModule =
    coreModule.taskTrackerModule

  @Provides @Singleton
  final def taskQueue(coreModule: CoreModule): LaunchQueue = coreModule.appOfferMatcherModule.taskQueue

  @Provides @Singleton
  final def appInfoService(appInfoModule: AppInfoModule): AppInfoService = appInfoModule.appInfoService

  @Provides @Singleton
  def pluginManager(coreModule: CoreModule): PluginManager = coreModule.pluginModule.pluginManager

  @Provides @Singleton
  def authorizer(coreModule: CoreModule): Authorizer = coreModule.authModule.authorizer

  @Provides @Singleton
  def authenticator(coreModule: CoreModule): Authenticator = coreModule.authModule.authenticator

  @Provides @Singleton
  def taskStatusUpdateSteps(
    notifyHealthCheckManagerStepImpl: NotifyHealthCheckManagerStepImpl,
    acknowledgeTaskUpdateStepImpl: AcknowledgeTaskUpdateStepImpl,
    notifyRateLimiterStepImpl: NotifyRateLimiterStepImpl,
    taskStatusEmitterPublishImpl: TaskStatusEmitterPublishStepImpl,
    postToEventStreamStepImpl: PostToEventStreamStepImpl,
    scaleAppUpdateStepImpl: ScaleAppUpdateStepImpl,
    notifyLaunchQueueStepImpl: NotifyLaunchQueueStepImpl,
    updateTaskTrackerStepImpl: UpdateTaskTrackerStepImpl): Seq[TaskStatusUpdateStep] = {

    // This is a sequence on purpose. The specified steps are executed in order for every
    // task status update.
    // This way we make sure that e.g. the taskTracker already reflects the changes for the update
    // (updateTaskTrackerStepImpl) before we notify the launch queue (notifyLaunchQueueStepImpl).

    Seq(

      // Update the task tracker _first_.
      //
      // Subsequent steps (for example, the health check subsystem) depend on
      // task tracker lookup to determine the routable host address for running
      // tasks.  In case this status update is the first TASK_RUNNING update
      // in IP-per-container mode, we need to store the assigned container
      // address reliably before attempting to initiate health checks, or
      // publish events to the bus.
      updateTaskTrackerStepImpl,
      ContinueOnErrorStep(notifyHealthCheckManagerStepImpl),
      ContinueOnErrorStep(notifyRateLimiterStepImpl),
      ContinueOnErrorStep(notifyLaunchQueueStepImpl),
      ContinueOnErrorStep(taskStatusEmitterPublishImpl),
      ContinueOnErrorStep(postToEventStreamStepImpl),
      ContinueOnErrorStep(scaleAppUpdateStepImpl),
      acknowledgeTaskUpdateStepImpl
    )
  }

  override def configure(): Unit = {
    bind(classOf[Clock]).toInstance(Clock())
    bind(classOf[CoreModule]).to(classOf[CoreModuleImpl]).in(Scopes.SINGLETON)

    // FIXME: Because of cycle breaking in guice, it is hard to not wire it with Guice directly
    bind(classOf[TaskStatusUpdateProcessor])
      .to(classOf[TaskStatusUpdateProcessorImpl])
      .asEagerSingleton()

    bind(classOf[AppInfoModule]).asEagerSingleton()
  }
}
