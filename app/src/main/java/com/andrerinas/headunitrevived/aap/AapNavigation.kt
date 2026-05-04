package com.andrerinas.headunitrevived.aap

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.aap.protocol.Channel
import com.andrerinas.headunitrevived.aap.protocol.proto.NavigationStatus
import com.andrerinas.headunitrevived.contract.NavigationUpdateIntent
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.Settings

/**
 * Handles navigation messages from the ID_NAV channel from any Android Auto-enabled app
 * (Google Maps, Yandex Maps, etc.). Shows notifications with turn-by-turn directions and current street.
 */
class AapNavigation(
    private val context: Context,
    private val settings: Settings
) {
    private var lastTurnDetail: NavigationStatus.NextTurnDetail? = null
    private var currentStreet: String = ""

    private var lastNavState: NavigationStatus.NavigationState? = null
    private var lastCurrentPosition: NavigationStatus.NavigationCurrentPosition? = null

    fun process(message: AapMessage): Boolean {
        if (message.channel != Channel.ID_NAV) return false

        return when (message.type) {
            NavigationStatus.MsgType.NEXTTURNDETAILS_VALUE -> {
                try {
                    val detail = message.parse(NavigationStatus.NextTurnDetail.newBuilder()).build()
                    lastTurnDetail = detail
                    currentStreet = detail.road.takeIf { it.isNotBlank() } ?: ""
                    AppLog.d("Nav: NextTurnDetail road=${detail.road} nextturn=${detail.nextturn}")
                    sendNavigationBroadcast(distanceMeters = null, timeSeconds = null, detail = detail)
                    if (settings.showNavigationNotifications) {
                        val actionText = nextEventToAction(detail.nextturn)
                        val street = currentStreet.ifBlank { detail.road.takeIf { r -> r.isNotBlank() } ?: "" }.ifBlank { "—" }
                        showNotification(distanceMeters = null, action = actionText, street = street)
                    }
                    true
                } catch (e: Exception) {
                    AppLog.e("Nav: failed to parse NextTurnDetail", e)
                    true
                }
            }
            NavigationStatus.MsgType.NEXTTURNDISTANCEANDTIME_VALUE -> {
                try {
                    val event = message.parse(NavigationStatus.NextTurnDistanceEvent.newBuilder()).build()
                    val distanceMeters = if (event.hasDistance()) event.distance else null
                    val timeSeconds = if (event.hasTime()) event.time else null
                    val detail = lastTurnDetail
                    val actionText = detail?.let { nextEventToAction(it.nextturn) } ?: context.getString(R.string.nav_action_unknown)
                    val street = currentStreet.ifBlank { detail?.road?.takeIf { r -> r.isNotBlank() } ?: "" }.ifBlank { "—" }
                    sendNavigationBroadcast(distanceMeters = distanceMeters, timeSeconds = timeSeconds, detail = detail)
                    if (settings.showNavigationNotifications) {
                        showNotification(
                            distanceMeters = distanceMeters,
                            action = actionText,
                            street = street
                        )
                    }
                    true
                } catch (e: Exception) {
                    AppLog.e("Nav: failed to parse NextTurnDistanceEvent", e)
                    true
                }
            }
            NavigationStatus.MsgType.NAVIGATION_STATE_VALUE -> {
                try {
                    val state = message.parse(NavigationStatus.NavigationState.newBuilder()).build()
                    lastNavState = state
                    val firstStep = state.stepsList.firstOrNull()
                    if (firstStep != null) {
                        currentStreet = firstStep.road?.name ?: ""
                        AppLog.d("Nav: NavigationState road=${currentStreet} type=${firstStep.maneuver?.type}")
                        
                        // Trigger initial broadcast for the new step
                        processNewProtocolUpdate()
                    }
                    true
                } catch (e: Exception) {
                    AppLog.e("Nav: failed to parse NavigationState", e)
                    true
                }
            }
            NavigationStatus.MsgType.NAVIGATION_CURRENT_POSITION_VALUE -> {
                try {
                    val pos = message.parse(NavigationStatus.NavigationCurrentPosition.newBuilder()).build()
                    lastCurrentPosition = pos
                    if (pos.hasCurrentRoad() && pos.currentRoad.name.isNotBlank()) {
                        currentStreet = pos.currentRoad.name
                    }
                    
                    processNewProtocolUpdate()
                    true
                } catch (e: Exception) {
                    AppLog.e("Nav: failed to parse NavigationCurrentPosition", e)
                    true
                }
            }
            else -> {
                AppLog.d("Nav: passthrough type ${message.type}")
                false
            }
        }
    }

    private fun processNewProtocolUpdate() {
        val state = lastNavState ?: return
        val pos = lastCurrentPosition
        val firstStep = state.stepsList.firstOrNull() ?: return
        val maneuver = firstStep.maneuver
        
        val distanceMeters = pos?.stepDistance?.distanceM?.toInt()
        val timeSeconds = pos?.destinationDistancesList?.firstOrNull()?.timeToArrivalS
        
        val road = firstStep.road?.name ?: currentStreet
        val nextEvent = mapNavigationTypeToNextEvent(maneuver?.type ?: NavigationStatus.NavigationManeuver.NavigationType.UNKNOWN)
        val turnSide = mapNavigationTypeToSide(maneuver?.type ?: NavigationStatus.NavigationManeuver.NavigationType.UNKNOWN)
        
        val turnNumber = maneuver?.roundaboutExitNumber
        val turnAngle = maneuver?.roundaboutExitAngle

        sendNavigationBroadcast(
            distanceMeters = distanceMeters,
            timeSeconds = timeSeconds,
            road = road,
            nextEventType = nextEvent,
            turnSide = turnSide,
            turnNumber = turnNumber,
            turnAngle = turnAngle
        )

        if (settings.showNavigationNotifications) {
            val actionText = nextEventToAction(nextEvent)
            val street = road.ifBlank { currentStreet }.ifBlank { "—" }
            showNotification(distanceMeters = distanceMeters, action = actionText, street = street)
        }
    }

    private fun sendNavigationBroadcast(
        distanceMeters: Int?,
        timeSeconds: Int?,
        detail: NavigationStatus.NextTurnDetail?
    ) {
        val road = (detail?.road?.takeIf { it.isNotBlank() } ?: currentStreet).ifBlank { "—" }
        val nextEventType = detail?.nextturn ?: NavigationStatus.NextTurnDetail.NextEvent.UNKNOWN
        val turnSide = detail?.side?.number
        val turnNumber = detail?.takeIf { it.hasTrunnumer() }?.trunnumer?.toInt()
        val turnAngle = detail?.takeIf { it.hasTurnangel() }?.turnangel?.toInt()
        
        sendNavigationBroadcast(
            distanceMeters = distanceMeters,
            timeSeconds = timeSeconds,
            road = road,
            nextEventType = nextEventType,
            turnSide = turnSide,
            turnNumber = turnNumber,
            turnAngle = turnAngle
        )
    }

    private fun sendNavigationBroadcast(
        distanceMeters: Int?,
        timeSeconds: Int?,
        road: String,
        nextEventType: NavigationStatus.NextTurnDetail.NextEvent,
        turnSide: Int?,
        turnNumber: Int?,
        turnAngle: Int?
    ) {
        val actionText = nextEventToAction(nextEventType)
        val intent = NavigationUpdateIntent(
            distanceMeters = distanceMeters,
            timeSeconds = timeSeconds,
            road = road,
            nextEventType = nextEventType.number,
            actionText = actionText,
            turnSide = turnSide,
            turnNumber = turnNumber,
            turnAngle = turnAngle
        )
        context.applicationContext.sendBroadcast(intent)
    }

    private fun mapNavigationTypeToNextEvent(type: NavigationStatus.NavigationManeuver.NavigationType): NavigationStatus.NextTurnDetail.NextEvent {
        return when (type) {
            NavigationStatus.NavigationManeuver.NavigationType.DEPART -> NavigationStatus.NextTurnDetail.NextEvent.DEPARTE
            NavigationStatus.NavigationManeuver.NavigationType.NAME_CHANGE -> NavigationStatus.NextTurnDetail.NextEvent.NAME_CHANGE
            NavigationStatus.NavigationManeuver.NavigationType.KEEP_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.KEEP_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.TURN_SLIGHT_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.TURN_SLIGHT_RIGHT -> NavigationStatus.NextTurnDetail.NextEvent.SLIGHT_TURN
            NavigationStatus.NavigationManeuver.NavigationType.TURN_NORMAL_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.TURN_NORMAL_RIGHT -> NavigationStatus.NextTurnDetail.NextEvent.TURN
            NavigationStatus.NavigationManeuver.NavigationType.TURN_SHARP_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.TURN_SHARP_RIGHT -> NavigationStatus.NextTurnDetail.NextEvent.SHARP_TURN
            NavigationStatus.NavigationManeuver.NavigationType.U_TURN_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.U_TURN_RIGHT -> NavigationStatus.NextTurnDetail.NextEvent.UTURN
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_SLIGHT_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_SLIGHT_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_NORMAL_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_NORMAL_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_SHARP_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_SHARP_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_U_TURN_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_U_TURN_RIGHT -> NavigationStatus.NextTurnDetail.NextEvent.ONRAMPE
            NavigationStatus.NavigationManeuver.NavigationType.OFF_RAMP_SLIGHT_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.OFF_RAMP_SLIGHT_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.OFF_RAMP_NORMAL_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.OFF_RAMP_NORMAL_RIGHT -> NavigationStatus.NextTurnDetail.NextEvent.OFFRAMP
            NavigationStatus.NavigationManeuver.NavigationType.FORK_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.FORK_RIGHT -> NavigationStatus.NextTurnDetail.NextEvent.FORME
            NavigationStatus.NavigationManeuver.NavigationType.MERGE_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.MERGE_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.MERGE_SIDE_UNSPECIFIED -> NavigationStatus.NextTurnDetail.NextEvent.MERGE
            NavigationStatus.NavigationManeuver.NavigationType.ROUNDABOUT_ENTER -> NavigationStatus.NextTurnDetail.NextEvent.ROUNDABOUT_ENTER
            NavigationStatus.NavigationManeuver.NavigationType.ROUNDABOUT_EXIT -> NavigationStatus.NextTurnDetail.NextEvent.ROUNDABOUT_EXIT
            NavigationStatus.NavigationManeuver.NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CW,
            NavigationStatus.NavigationManeuver.NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CW_WITH_ANGLE,
            NavigationStatus.NavigationManeuver.NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CCW,
            NavigationStatus.NavigationManeuver.NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CCW_WITH_ANGLE -> NavigationStatus.NextTurnDetail.NextEvent.ROUNDABOUT_ENTER_AND_EXIT
            NavigationStatus.NavigationManeuver.NavigationType.STRAIGHT -> NavigationStatus.NextTurnDetail.NextEvent.STRAIGHTE
            NavigationStatus.NavigationManeuver.NavigationType.FERRY_BOAT -> NavigationStatus.NextTurnDetail.NextEvent.FERRY_BOAT
            NavigationStatus.NavigationManeuver.NavigationType.FERRY_TRAIN -> NavigationStatus.NextTurnDetail.NextEvent.FERRY_TRAINE
            NavigationStatus.NavigationManeuver.NavigationType.DESTINATION,
            NavigationStatus.NavigationManeuver.NavigationType.DESTINATION_STRAIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.DESTINATION_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.DESTINATION_RIGHT -> NavigationStatus.NextTurnDetail.NextEvent.DESTINATION
            else -> NavigationStatus.NextTurnDetail.NextEvent.UNKNOWN
        }
    }

    private fun mapNavigationTypeToSide(type: NavigationStatus.NavigationManeuver.NavigationType): Int {
        return when (type) {
            NavigationStatus.NavigationManeuver.NavigationType.KEEP_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.TURN_SLIGHT_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.TURN_NORMAL_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.TURN_SHARP_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.U_TURN_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_SLIGHT_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_NORMAL_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_SHARP_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_U_TURN_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.OFF_RAMP_SLIGHT_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.OFF_RAMP_NORMAL_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.FORK_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.MERGE_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CCW,
            NavigationStatus.NavigationManeuver.NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CCW_WITH_ANGLE,
            NavigationStatus.NavigationManeuver.NavigationType.DESTINATION_LEFT -> NavigationStatus.NextTurnDetail.Side.LEFT_VALUE

            NavigationStatus.NavigationManeuver.NavigationType.KEEP_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.TURN_SLIGHT_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.TURN_NORMAL_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.TURN_SHARP_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.U_TURN_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_SLIGHT_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_NORMAL_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_SHARP_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_U_TURN_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.OFF_RAMP_SLIGHT_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.OFF_RAMP_NORMAL_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.FORK_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.MERGE_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CW,
            NavigationStatus.NavigationManeuver.NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CW_WITH_ANGLE,
            NavigationStatus.NavigationManeuver.NavigationType.DESTINATION_RIGHT -> NavigationStatus.NextTurnDetail.Side.RIGHT_VALUE

            else -> NavigationStatus.NextTurnDetail.Side.UNSPECIFIED_VALUE
        }
    }

    private fun showNotification(distanceMeters: Int?, action: String, street: String) {
        val appContext = context.applicationContext
        val title = if (distanceMeters != null && distanceMeters >= 0) {
            context.getString(R.string.nav_notification_title_format, distanceMeters, action)
        } else {
            action
        }
        val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val notification = NotificationCompat.Builder(appContext, NAV_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_aa)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.nav_notification_street_format, street))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    appContext,
                    0,
                    AapProjectionActivity.intent(appContext),
                    pendingIntentFlags
                )
            )
            .build()
        (appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NAV_NOTIFICATION_ID, notification)
    }

    private fun nextEventToAction(nextEvent: NavigationStatus.NextTurnDetail.NextEvent): String {
        return when (nextEvent) {
            NavigationStatus.NextTurnDetail.NextEvent.UNKNOWN -> context.getString(R.string.nav_action_unknown)
            NavigationStatus.NextTurnDetail.NextEvent.DEPARTE -> context.getString(R.string.nav_action_depart)
            NavigationStatus.NextTurnDetail.NextEvent.NAME_CHANGE -> context.getString(R.string.nav_action_name_change)
            NavigationStatus.NextTurnDetail.NextEvent.SLIGHT_TURN -> context.getString(R.string.nav_action_slight_turn)
            NavigationStatus.NextTurnDetail.NextEvent.TURN -> context.getString(R.string.nav_action_turn)
            NavigationStatus.NextTurnDetail.NextEvent.SHARP_TURN -> context.getString(R.string.nav_action_sharp_turn)
            NavigationStatus.NextTurnDetail.NextEvent.UTURN -> context.getString(R.string.nav_action_uturn)
            NavigationStatus.NextTurnDetail.NextEvent.ONRAMPE -> context.getString(R.string.nav_action_on_ramp)
            NavigationStatus.NextTurnDetail.NextEvent.OFFRAMP -> context.getString(R.string.nav_action_off_ramp)
            NavigationStatus.NextTurnDetail.NextEvent.FORME -> context.getString(R.string.nav_action_merge)
            NavigationStatus.NextTurnDetail.NextEvent.MERGE -> context.getString(R.string.nav_action_merge)
            NavigationStatus.NextTurnDetail.NextEvent.ROUNDABOUT_ENTER -> context.getString(R.string.nav_action_roundabout_enter)
            NavigationStatus.NextTurnDetail.NextEvent.ROUNDABOUT_EXIT -> context.getString(R.string.nav_action_roundabout_exit)
            NavigationStatus.NextTurnDetail.NextEvent.ROUNDABOUT_ENTER_AND_EXIT -> context.getString(R.string.nav_action_roundabout)
            NavigationStatus.NextTurnDetail.NextEvent.STRAIGHTE -> context.getString(R.string.nav_action_straight)
            NavigationStatus.NextTurnDetail.NextEvent.FERRY_BOAT -> context.getString(R.string.nav_action_ferry)
            NavigationStatus.NextTurnDetail.NextEvent.FERRY_TRAINE -> context.getString(R.string.nav_action_ferry_train)
            NavigationStatus.NextTurnDetail.NextEvent.DESTINATION -> context.getString(R.string.nav_action_destination)
        }
    }

    companion object {
        const val NAV_CHANNEL_ID = "headunit_navigation"
        private const val NAV_NOTIFICATION_ID = 2

        fun createNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    NAV_CHANNEL_ID,
                    context.getString(R.string.nav_notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = context.getString(R.string.nav_notification_channel_description)
                    setShowBadge(false)
                }
                (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .createNotificationChannel(channel)
            }
        }
    }
}
