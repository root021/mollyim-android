package org.thoughtcrime.securesms.messages

import android.app.Application
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationCompat
import kotlinx.collections.immutable.toImmutableSet
import org.signal.core.util.concurrent.SignalExecutors
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.crypto.ReentrantSessionLock
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.groups.GroupsV2ProcessingLock
import org.thoughtcrime.securesms.jobmanager.impl.BackoffUtil
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint
import org.thoughtcrime.securesms.jobs.PushProcessMessageErrorJob
import org.thoughtcrime.securesms.jobs.PushProcessMessageJob
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.keyvalue.isDecisionPending
import org.thoughtcrime.securesms.messages.MessageDecryptor.FollowUpOperation
import org.thoughtcrime.securesms.messages.protocol.BufferedProtocolStore
import org.thoughtcrime.securesms.notifications.NotificationChannels
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.service.SafeForegroundService
import org.thoughtcrime.securesms.util.AlarmSleepTimer
import org.thoughtcrime.securesms.util.AppForegroundObserver
import org.thoughtcrime.securesms.util.RemoteConfig
import org.thoughtcrime.securesms.util.SignalLocalMetrics
import org.thoughtcrime.securesms.util.asChain
import org.whispersystems.signalservice.api.SignalWebSocket
import org.whispersystems.signalservice.api.push.ServiceId
import org.whispersystems.signalservice.api.util.SleepTimer
import org.whispersystems.signalservice.api.util.UptimeSleepTimer
import org.whispersystems.signalservice.api.websocket.WebSocketConnectionState
import org.whispersystems.signalservice.api.websocket.WebSocketUnavailableException
import org.whispersystems.signalservice.internal.push.Envelope
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.round
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The application-level manager of our websocket connection.
 *
 *
 * This class is responsible for opening/closing the websocket based on the app's state and observing new inbound messages received on the websocket.
 */
class IncomingMessageObserver(private val context: Application, private val signalWebSocket: SignalWebSocket) {

  companion object {
    private val TAG = Log.tag(IncomingMessageObserver::class.java)

    /** How long we wait for the websocket to time out before we try to connect again. */
    private val websocketReadTimeout: Long
      get() = if (censored) 30.seconds.inWholeMilliseconds else 1.minutes.inWholeMilliseconds

    /** How long a keep-alive token is allowed to keep the websocket open for. These are usually used for calling + FCM messages. */
    private val keepAliveTokenMaxAge: Long
      get() = if (censored) 2.minutes.inWholeMilliseconds else 5.minutes.inWholeMilliseconds

    /** How long the websocket is allowed to keep running after the user backgrounds the app. Higher numbers allow us to rely on FCM less. */
    private val maxBackgroundTime: Long
      get() = if (censored) 10.seconds.inWholeMilliseconds else 2.minutes.inWholeMilliseconds

    const val FOREGROUND_ID = 313399

    private val censored: Boolean
      get() = AppDependencies.signalServiceNetworkAccess.isCensored()
  }

  private val decryptionDrainedListeners: MutableList<Runnable> = CopyOnWriteArrayList()
  private val keepAliveTokens: MutableMap<String, Long> = mutableMapOf()
  private val keepAlivePurgeCallbacks: MutableMap<String, MutableList<Runnable>> = mutableMapOf()

  private val lock: ReentrantLock = ReentrantLock()
  private val connectionNecessarySemaphore = Semaphore(0)
  private val networkConnectionListener = NetworkConnectionListener(context) { isNetworkUnavailable ->
    lock.withLock {
      AppDependencies.libsignalNetwork.onNetworkChange()
      if (isNetworkUnavailable()) {
        Log.w(TAG, "Lost network connection. Shutting down our websocket connections and resetting the drained state.")
        decryptionDrained = false
        disconnect()
      }
      connectionNecessarySemaphore.release()
    }
  }

  private val messageContentProcessor = MessageContentProcessor(context)

  private var appVisible = false
  private var lastInteractionTime: Long = System.currentTimeMillis()

  @Volatile
  private var terminated = false

  @Volatile
  var decryptionDrained = false
    private set

  init {
    MessageRetrievalThread().start()

    val registered = SignalStore.account.isRegistered
    val pushAvailable = SignalStore.account.pushAvailable
    val forceWebsocket = SignalStore.internal.isWebsocketModeForced

    if (registered && (!pushAvailable || forceWebsocket)) {
      ForegroundService.startIfNotRunning(context)
    }

    AppForegroundObserver.addListener(object : AppForegroundObserver.Listener {
      override fun onForeground() {
        onAppForegrounded()
      }

      override fun onBackground() {
        onAppBackgrounded()
      }
    })

    networkConnectionListener.register()
  }

  fun notifyRegistrationStateChanged() {
    connectionNecessarySemaphore.release()
  }

  fun addDecryptionDrainedListener(listener: Runnable) {
    decryptionDrainedListeners.add(listener)
    if (decryptionDrained) {
      listener.run()
    }
  }

  fun removeDecryptionDrainedListener(listener: Runnable) {
    decryptionDrainedListeners.remove(listener)
  }

  private fun onAppForegrounded() {
    lock.withLock {
      appVisible = true
      BackgroundService.start(context)
      connectionNecessarySemaphore.release()
    }
  }

  private fun onAppBackgrounded() {
    lock.withLock {
      appVisible = false
      lastInteractionTime = System.currentTimeMillis()
      connectionNecessarySemaphore.release()
    }
  }

  private fun shouldKeepAliveUnidentified(): Boolean {
    val timeIdle = lock.withLock {
      if (appVisible) 0 else System.currentTimeMillis() - lastInteractionTime
    }
    return timeIdle <= websocketReadTimeout
  }

  private fun isConnectionNecessary(): Boolean {
    val timeIdle: Long
    val keepAliveEntries: Set<Pair<String, Long>>
    val appVisibleSnapshot: Boolean

    lock.withLock {
      appVisibleSnapshot = appVisible
      timeIdle = if (appVisibleSnapshot) 0 else System.currentTimeMillis() - lastInteractionTime

      val keepAliveCutoffTime = System.currentTimeMillis() - keepAliveTokenMaxAge
      keepAliveEntries = keepAliveTokens.entries.mapNotNull { (key, createTime) ->
        if (createTime < keepAliveCutoffTime) {
          Log.d(TAG, "Removed old keep web socket keep alive token $key")
          keepAlivePurgeCallbacks.remove(key)?.forEach { it.run() }
          null
        } else {
          key to createTime
        }
      }.toImmutableSet()
    }

    val registered = SignalStore.account.isRegistered
    val pushAvailable = SignalStore.account.pushAvailable
    val hasNetwork = NetworkConstraint.isMet(context)
    val hasProxy = AppDependencies.networkManager.isProxyEnabled
    val forceWebsocket = SignalStore.internal.isWebsocketModeForced
    val isRestoreDecisionPending = RemoteConfig.restoreAfterRegistration && SignalStore.registration.restoreDecisionState.isDecisionPending

    val lastInteractionString = if (appVisibleSnapshot) "N/A" else timeIdle.toString() + " ms (" + (if (timeIdle < maxBackgroundTime) "within limit" else "over limit") + ")"
    val conclusion = registered &&
      (appVisibleSnapshot || timeIdle < maxBackgroundTime || !pushAvailable || keepAliveEntries.isNotEmpty()) &&
      hasNetwork &&
      !isRestoreDecisionPending

    val needsConnectionString = if (conclusion) "Needs Connection" else "Does Not Need Connection"

    Log.d(TAG, "[$needsConnectionString] Network: $hasNetwork, Foreground: $appVisibleSnapshot, Time Since Last Interaction: $lastInteractionString, PushAvailable: $pushAvailable, Stay open requests: $keepAliveEntries, Registered: $registered, Proxy: $hasProxy, Force websocket: $forceWebsocket, Pending restore: $isRestoreDecisionPending")
    return conclusion
  }

  private fun waitForConnectionNecessary() {
    try {
      connectionNecessarySemaphore.drainPermits()
      while (!isConnectionNecessary()) {
        val numberDrained = connectionNecessarySemaphore.drainPermits()
        if (numberDrained == 0) {
          connectionNecessarySemaphore.acquire()
        }
      }
    } catch (e: InterruptedException) {
      throw AssertionError(e)
    }
  }

  fun terminateAsync() {
    Log.w(TAG, "Termination Enqueued! ${this.hashCode()}", Throwable())
    networkConnectionListener.unregister()
    ForegroundService.stopIfRunning(context)
    SignalExecutors.BOUNDED.execute {
      Log.w(TAG, "Beginning termination. ${this.hashCode()}")
      terminated = true
      disconnect()
    }
  }

  private fun disconnect() {
    signalWebSocket.disconnect()
  }

  @JvmOverloads
  fun registerKeepAliveToken(key: String, runnable: Runnable? = null) {
    lock.withLock {
      keepAliveTokens[key] = System.currentTimeMillis()
      if (runnable != null) {
        if (!keepAlivePurgeCallbacks.containsKey(key)) {
          keepAlivePurgeCallbacks[key] = ArrayList()
        }
        keepAlivePurgeCallbacks[key]?.add(runnable)
      }
      lastInteractionTime = System.currentTimeMillis()
      connectionNecessarySemaphore.release()
    }
  }

  fun removeKeepAliveToken(key: String) {
    lock.withLock {
      keepAliveTokens.remove(key)
      keepAlivePurgeCallbacks.remove(key)
      lastInteractionTime = System.currentTimeMillis()
      connectionNecessarySemaphore.release()
    }
  }

  @VisibleForTesting
  fun processEnvelope(bufferedProtocolStore: BufferedProtocolStore, envelope: Envelope, serverDeliveredTimestamp: Long): List<FollowUpOperation>? {
    return when (envelope.type) {
      Envelope.Type.SERVER_DELIVERY_RECEIPT -> {
        processReceipt(envelope)
        null
      }

      Envelope.Type.PREKEY_BUNDLE,
      Envelope.Type.CIPHERTEXT,
      Envelope.Type.UNIDENTIFIED_SENDER,
      Envelope.Type.PLAINTEXT_CONTENT -> {
        processMessage(bufferedProtocolStore, envelope, serverDeliveredTimestamp)
      }

      else -> {
        Log.w(TAG, "Received envelope of unknown type: " + envelope.type)
        null
      }
    }
  }

  private fun processMessage(bufferedProtocolStore: BufferedProtocolStore, envelope: Envelope, serverDeliveredTimestamp: Long): List<FollowUpOperation> {
    val localReceiveMetric = SignalLocalMetrics.MessageReceive.start()
    val result = MessageDecryptor.decrypt(context, bufferedProtocolStore, envelope, serverDeliveredTimestamp)
    localReceiveMetric.onEnvelopeDecrypted()

    SignalLocalMetrics.MessageLatency.onMessageReceived(envelope.serverTimestamp!!, serverDeliveredTimestamp, envelope.urgent!!)
    when (result) {
      is MessageDecryptor.Result.Success -> {
        val job = PushProcessMessageJob.processOrDefer(messageContentProcessor, result, localReceiveMetric)
        if (job != null) {
          return result.followUpOperations + FollowUpOperation { job.asChain() }
        }
      }
      is MessageDecryptor.Result.Error -> {
        return result.followUpOperations + FollowUpOperation {
          PushProcessMessageErrorJob(
            result.toMessageState(),
            result.errorMetadata.toExceptionMetadata(),
            result.envelope.timestamp!!
          ).asChain()
        }
      }
      is MessageDecryptor.Result.Ignore -> {
        // No action needed
      }
      else -> {
        throw AssertionError("Unexpected result! ${result.javaClass.simpleName}")
      }
    }

    return result.followUpOperations
  }

  private fun processReceipt(envelope: Envelope) {
    val serviceId = ServiceId.parseOrNull(envelope.sourceServiceId)
    if (serviceId == null) {
      Log.w(TAG, "Invalid envelope sourceServiceId!")
      return
    }

    val senderId = RecipientId.from(serviceId)

    Log.i(TAG, "Received server receipt. Sender: $senderId, Device: ${envelope.sourceDevice}, Timestamp: ${envelope.timestamp}")
    SignalDatabase.messages.incrementDeliveryReceiptCount(envelope.timestamp!!, senderId, System.currentTimeMillis())
    SignalDatabase.messageLog.deleteEntryForRecipient(envelope.timestamp!!, senderId, envelope.sourceDevice!!)
  }

  private fun MessageDecryptor.Result.toMessageState(): MessageState {
    return when (this) {
      is MessageDecryptor.Result.DecryptionError -> MessageState.DECRYPTION_ERROR
      is MessageDecryptor.Result.Ignore -> MessageState.NOOP
      is MessageDecryptor.Result.InvalidVersion -> MessageState.INVALID_VERSION
      is MessageDecryptor.Result.LegacyMessage -> MessageState.LEGACY_MESSAGE
      is MessageDecryptor.Result.Success -> MessageState.DECRYPTED_OK
      is MessageDecryptor.Result.UnsupportedDataMessage -> MessageState.UNSUPPORTED_DATA_MESSAGE
    }
  }

  private fun MessageDecryptor.ErrorMetadata.toExceptionMetadata(): ExceptionMetadata {
    return ExceptionMetadata(
      this.sender,
      this.senderDevice,
      this.groupId
    )
  }

  private inner class MessageRetrievalThread : Thread("MessageRetrievalService"), Thread.UncaughtExceptionHandler {

    private var sleepTimer: SleepTimer

    init {
      Log.i(TAG, "Initializing! (${this.hashCode()})")
      uncaughtExceptionHandler = this

      sleepTimer = if (!SignalStore.account.pushAvailable || SignalStore.internal.isWebsocketModeForced) AlarmSleepTimer(context) else UptimeSleepTimer()
    }

    override fun run() {
      var attempts = 0

      while (!terminated) {
        Log.i(TAG, "Waiting for websocket state change....")
        if (attempts > 1) {
          val backoff = BackoffUtil.exponentialBackoff(attempts, TimeUnit.SECONDS.toMillis(30))
          Log.w(TAG, "Too many failed connection attempts,  attempts: $attempts backing off: $backoff")
          sleepTimer.sleep(backoff)
        }

        waitForConnectionNecessary()
        Log.i(TAG, "Making websocket connection....")

        val webSocketDisposable = signalWebSocket.webSocketState.subscribe { state: WebSocketConnectionState ->
          Log.d(TAG, "WebSocket State: $state")

          // Any change to a non-connected state means that we are not drained
          if (state != WebSocketConnectionState.CONNECTED) {
            decryptionDrained = false
          }
        }

        signalWebSocket.connect(shouldKeepAliveUnidentified())
        try {
          while (!terminated && isConnectionNecessary()) {
            try {
              Log.d(TAG, "Reading message...")

              val hasMore = signalWebSocket.readMessageBatch(websocketReadTimeout, 30) { batch ->
                Log.i(TAG, "Retrieved ${batch.size} envelopes!")
                val bufferedStore = BufferedProtocolStore.create()

                val startTime = System.currentTimeMillis()
                GroupsV2ProcessingLock.acquireGroupProcessingLock().use {
                  ReentrantSessionLock.INSTANCE.acquire().use {
                    batch.forEach { response ->
                      Log.d(TAG, "Beginning database transaction...")
                      val followUpOperations = SignalDatabase.runInTransaction { db ->
                        val followUps: List<FollowUpOperation>? = processEnvelope(bufferedStore, response.envelope, response.serverDeliveredTimestamp)
                        bufferedStore.flushToDisk()
                        followUps
                      }
                      Log.d(TAG, "Ended database transaction.")

                      if (followUpOperations != null) {
                        Log.d(TAG, "Running ${followUpOperations.size} follow-up operations...")
                        val jobs = followUpOperations.mapNotNull { it.run() }
                        AppDependencies.jobManager.addAllChains(jobs)
                      }

                      signalWebSocket.sendAck(response)
                    }
                  }
                }
                val duration = System.currentTimeMillis() - startTime
                val timePerMessage: Float = duration / batch.size.toFloat()
                Log.d(TAG, "Decrypted ${batch.size} envelopes in $duration ms (~${round(timePerMessage * 100) / 100} ms per message)")
              }
              attempts = 0
              SignalLocalMetrics.PushWebsocketFetch.onProcessedBatch()

              if (!hasMore && !decryptionDrained) {
                Log.i(TAG, "Decryptions newly-drained.")
                decryptionDrained = true

                for (listener in decryptionDrainedListeners.toList()) {
                  listener.run()
                }
              } else if (!hasMore) {
                Log.w(TAG, "Got tombstone, but we thought the network was already drained!")
              }
            } catch (e: WebSocketUnavailableException) {
              Log.i(TAG, "Pipe unexpectedly unavailable, connecting")
              signalWebSocket.connect(shouldKeepAliveUnidentified())
            } catch (e: TimeoutException) {
              Log.w(TAG, "Application level read timeout...")
              attempts = 0
            }
            signalWebSocket.setKeepAliveUnidentified(shouldKeepAliveUnidentified())
          }

          if (!appVisible) {
            BackgroundService.stop(context)
          }
        } catch (e: Throwable) {
          attempts++
          Log.w(TAG, e)
        } finally {
          Log.w(TAG, "Shutting down pipe...")
          disconnect()
          webSocketDisposable.dispose()
        }
        Log.i(TAG, "Looping...")
      }
      Log.w(TAG, "Terminated! (${this.hashCode()})")
    }

    override fun uncaughtException(t: Thread, e: Throwable) {
      Log.w(TAG, "Uncaught exception in message thread!", e)
    }
  }

  class ForegroundService : SafeForegroundService() {

    companion object {
      private val startPending = AtomicBoolean(false)
      private val stopPending = AtomicBoolean(false)

      fun startIfNotRunning(context: Context) {
        if (startPending.compareAndSet(false, true)) {
          SignalExecutors.SERIAL.execute {
            val started = start(context, ForegroundService::class.java)
            if (!started) {
              Log.w(TAG, "Unable to start foreground service for websocket!")
            }
            startPending.set(false)
          }
        }
      }

      fun stopIfRunning(context: Context) {
        if (stopPending.compareAndSet(false, true)) {
          SignalExecutors.SERIAL.execute {
            stop(context, ForegroundService::class.java)
            stopPending.set(false)
          }
        }
      }
    }

    override val tag: String = TAG
    override val notificationId: Int = FOREGROUND_ID

    override fun getForegroundNotification(intent: Intent): Notification {
      val notification = NotificationCompat.Builder(applicationContext, NotificationChannels.BACKGROUND)
        .setContentTitle(applicationContext.getString(R.string.IncomingMessageObserver_websocket_service))
        .setContentText(applicationContext.getString(R.string.MessageRetrievalService_ready_to_receive_messages))
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setWhen(0)
        .setSmallIcon(R.drawable.ic_notification_websocket)
        .build()
      return notification
    }
  }

  /**
   * A service that exists just to encourage the system to keep our process alive a little longer.
   */
  class BackgroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
      Log.d(TAG, "Background service started.")
      return START_STICKY
    }

    override fun onDestroy() {
      Log.d(TAG, "Background service destroyed.")
    }

    companion object {
      fun start(context: Context) {
        try {
          context.startService(Intent(context, BackgroundService::class.java))
        } catch (e: Exception) {
          Log.w(TAG, "Failed to start background service.", e)
        }
      }

      fun stop(context: Context) {
        context.stopService(Intent(context, BackgroundService::class.java))
      }
    }
  }
}
