package app.aaps.plugins.sync.nsclientV3.services

import android.content.Intent
import android.os.IBinder
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.nsclient.StoreDataForDb
import app.aaps.core.interfaces.rx.events.EventNSClientNewLog
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.StringKey
import app.aaps.plugins.sync.nsShared.NSAlarmObject
import app.aaps.plugins.sync.nsShared.NsIncomingDataProcessor
import app.aaps.plugins.sync.nsclient.data.NSDeviceStatusHandler
import app.aaps.plugins.sync.nsclientV3.NSClientV3Plugin
import app.aaps.plugins.sync.nsclientV3.keys.NsclientBooleanKey
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.whenever

class NSClientV3ServiceTest : TestBaseWithProfile() {

    @Mock lateinit var nsIncomingDataProcessor: NsIncomingDataProcessor
    @Mock lateinit var storeDataForDb: StoreDataForDb
    @Mock lateinit var uiInteraction: UiInteraction
    @Mock lateinit var nsDeviceStatusHandler: NSDeviceStatusHandler
    @Mock lateinit var nsClientV3Plugin: NSClientV3Plugin
    @Mock lateinit var iobCobCalculator: IobCobCalculator

    private lateinit var sut: NSClientV3Service

    @BeforeEach
    fun init() {
        sut = NSClientV3Service().also {
            it.aapsLogger = aapsLogger
            it.rxBus = rxBus
            it.rh = rh
            it.preferences = preferences
            it.fabricPrivacy = fabricPrivacy
            it.nsClientV3Plugin = nsClientV3Plugin
            it.config = config
            it.nsIncomingDataProcessor = nsIncomingDataProcessor
            it.storeDataForDb = storeDataForDb
            it.uiInteraction = uiInteraction
            it.nsDeviceStatusHandler = nsDeviceStatusHandler
            it.iobCobCalculator = iobCobCalculator
        }
    }

    @Test
    fun `initializeWebSockets does not create sockets when URL is empty`() {
        whenever(preferences.get(StringKey.NsClientUrl)).thenReturn("")

        sut.initializeWebSockets("Test")

        assertThat(sut.storageSocket).isNull()
        assertThat(sut.alarmSocket).isNull()
        assertThat(sut.wsConnected).isFalse()
    }

    @Test
    fun `initializeWebSockets creates storage socket when URL is valid and allowed`() {
        whenever(preferences.get(StringKey.NsClientUrl)).thenReturn("http://something")
        whenever(preferences.get(BooleanKey.NsClientNotificationsFromAnnouncements)).thenReturn(false)
        whenever(preferences.get(BooleanKey.NsClientNotificationsFromAlarms)).thenReturn(false)
        whenever(preferences.get(NsclientBooleanKey.NsPaused)).thenReturn(false)
        whenever(nsClientV3Plugin.isAllowed).thenReturn(true)

        sut.initializeWebSockets("Test")

        assertThat(sut.storageSocket).isNotNull()
        assertThat(sut.alarmSocket).isNull()
        sut.shutdownWebsockets()
    }

    @Test
    fun `initializeWebSockets creates both sockets when notifications enabled`() {
        whenever(preferences.get(StringKey.NsClientUrl)).thenReturn("http://something")
        whenever(preferences.get(BooleanKey.NsClientNotificationsFromAnnouncements)).thenReturn(true)
        whenever(preferences.get(BooleanKey.NsClientNotificationsFromAlarms)).thenReturn(false)
        whenever(preferences.get(NsclientBooleanKey.NsPaused)).thenReturn(false)
        whenever(nsClientV3Plugin.isAllowed).thenReturn(true)

        sut.initializeWebSockets("Test")

        assertThat(sut.storageSocket).isNotNull()
        assertThat(sut.alarmSocket).isNotNull()
        sut.shutdownWebsockets()
    }

    @Test
    fun `initializeWebSockets creates alarm socket when alarms enabled`() {
        whenever(preferences.get(StringKey.NsClientUrl)).thenReturn("http://something")
        whenever(preferences.get(BooleanKey.NsClientNotificationsFromAnnouncements)).thenReturn(false)
        whenever(preferences.get(BooleanKey.NsClientNotificationsFromAlarms)).thenReturn(true)
        whenever(preferences.get(NsclientBooleanKey.NsPaused)).thenReturn(false)
        whenever(nsClientV3Plugin.isAllowed).thenReturn(true)

        sut.initializeWebSockets("Test")

        assertThat(sut.storageSocket).isNotNull()
        assertThat(sut.alarmSocket).isNotNull()
        sut.shutdownWebsockets()
    }

    @Test
    fun `initializeWebSockets sends paused event when paused`() {
        whenever(preferences.get(StringKey.NsClientUrl)).thenReturn("http://something")
        whenever(preferences.get(NsclientBooleanKey.NsPaused)).thenReturn(true)
        whenever(nsClientV3Plugin.isAllowed).thenReturn(true)

        val events = mutableListOf<EventNSClientNewLog>()
        val subscription = rxBus.toObservable(EventNSClientNewLog::class.java).subscribe { events.add(it) }

        sut.initializeWebSockets("Test")

        assertThat(events.any { it.action == "● WS" && it.logText == "paused" }).isTrue()
        assertThat(sut.storageSocket).isNull()
        assertThat(sut.alarmSocket).isNull()
        subscription.dispose()
    }

    @Test
    fun `initializeWebSockets sends blocking reason when not allowed`() {
        whenever(preferences.get(StringKey.NsClientUrl)).thenReturn("http://something")
        whenever(nsClientV3Plugin.isAllowed).thenReturn(false)
        whenever(nsClientV3Plugin.blockingReason).thenReturn("No network connection")

        val events = mutableListOf<EventNSClientNewLog>()
        val subscription = rxBus.toObservable(EventNSClientNewLog::class.java).subscribe { events.add(it) }

        sut.initializeWebSockets("Test")

        assertThat(events.any { it.action == "● WS" && it.logText == "No network connection" }).isTrue()
        assertThat(sut.storageSocket).isNull()
        assertThat(sut.alarmSocket).isNull()
        subscription.dispose()
    }

    @Test
    fun `initializeWebSockets normalizes URL by removing trailing slash`() {
        whenever(preferences.get(StringKey.NsClientUrl)).thenReturn("HTTP://SOMETHING/")
        whenever(preferences.get(BooleanKey.NsClientNotificationsFromAnnouncements)).thenReturn(false)
        whenever(preferences.get(BooleanKey.NsClientNotificationsFromAlarms)).thenReturn(false)
        whenever(preferences.get(NsclientBooleanKey.NsPaused)).thenReturn(false)
        whenever(nsClientV3Plugin.isAllowed).thenReturn(true)

        sut.initializeWebSockets("Test")

        assertThat(sut.storageSocket).isNotNull()
        sut.shutdownWebsockets()
    }

    @Test
    fun `shutdownWebsockets clears sockets and sets wsConnected to false`() {
        whenever(preferences.get(StringKey.NsClientUrl)).thenReturn("http://something")
        whenever(preferences.get(BooleanKey.NsClientNotificationsFromAnnouncements)).thenReturn(true)
        whenever(preferences.get(NsclientBooleanKey.NsPaused)).thenReturn(false)
        whenever(nsClientV3Plugin.isAllowed).thenReturn(true)

        sut.initializeWebSockets("Test")
        assertThat(sut.storageSocket).isNotNull()
        assertThat(sut.alarmSocket).isNotNull()

        sut.shutdownWebsockets()

        assertThat(sut.storageSocket).isNull()
        assertThat(sut.alarmSocket).isNull()
        assertThat(sut.wsConnected).isFalse()
    }

    @Test
    fun `shutdownWebsockets handles null sockets gracefully`() {
        assertThat(sut.storageSocket).isNull()
        assertThat(sut.alarmSocket).isNull()

        sut.shutdownWebsockets()

        assertThat(sut.storageSocket).isNull()
        assertThat(sut.alarmSocket).isNull()
        assertThat(sut.wsConnected).isFalse()
    }

    @Test
    fun `onBind returns LocalBinder`() {
        val intent = Intent()

        val binder = sut.onBind(intent)

        assertThat(binder).isNotNull()
        assertThat(binder).isInstanceOf(IBinder::class.java)
    }

    @Test
    fun `LocalBinder returns service instance`() {
        val intent = Intent()
        val binder = sut.onBind(intent) as NSClientV3Service.LocalBinder

        val serviceInstance = binder.serviceInstance

        assertThat(serviceInstance).isEqualTo(sut)
    }

    @Test
    fun `wsConnected is initially false`() {
        assertThat(sut.wsConnected).isFalse()
    }

    @Test
    fun `initializeWebSockets sends connect events`() {
        whenever(preferences.get(StringKey.NsClientUrl)).thenReturn("http://something")
        whenever(preferences.get(BooleanKey.NsClientNotificationsFromAnnouncements)).thenReturn(true)
        whenever(preferences.get(NsclientBooleanKey.NsPaused)).thenReturn(false)
        whenever(nsClientV3Plugin.isAllowed).thenReturn(true)

        val events = mutableListOf<EventNSClientNewLog>()
        val subscription = rxBus.toObservable(EventNSClientNewLog::class.java).subscribe { events.add(it) }

        sut.initializeWebSockets("TestReason")

        assertThat(events.any { it.action == "► WS" && it.logText?.contains("do connect storage TestReason") ?: false }).isTrue()
        assertThat(events.any { it.action == "► WS" && it.logText?.contains("do connect alarm TestReason") ?: false }).isTrue()
        subscription.dispose()
        sut.shutdownWebsockets()
    }

    private fun lowAlarm(level: Int = 1) = NSAlarmObject(JSONObject().put("eventName", "low").put("level", level))
    private fun highAlarm() = NSAlarmObject(JSONObject().put("eventName", "high"))

    private fun mockIobGate(iob: Double, threshold: Double = 0.0, enabled: Boolean = true) {
        whenever(preferences.get(BooleanKey.NsClientAlarmLowGateOnIob)).thenReturn(enabled)
        whenever(preferences.get(DoubleKey.NsClientAlarmLowIobThreshold)).thenReturn(threshold)
        whenever(iobCobCalculator.calculateIobFromBolus()).thenReturn(IobTotal(time = 0, iob = iob))
        whenever(iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended()).thenReturn(IobTotal(time = 0, basaliob = 0.0))
    }

    @Test
    fun `shouldSuppressLowAlarm fires when gate disabled`() {
        mockIobGate(iob = -1.0, enabled = false)
        assertThat(sut.shouldSuppressLowAlarm(lowAlarm())).isFalse()
    }

    @Test
    fun `shouldSuppressLowAlarm ignores high alarms`() {
        mockIobGate(iob = -1.0)
        assertThat(sut.shouldSuppressLowAlarm(highAlarm())).isFalse()
    }

    @Test
    fun `shouldSuppressLowAlarm fires when IOB above threshold`() {
        mockIobGate(iob = 0.5, threshold = 0.0)
        assertThat(sut.shouldSuppressLowAlarm(lowAlarm())).isFalse()
    }

    @Test
    fun `shouldSuppressLowAlarm suppresses when IOB at threshold`() {
        mockIobGate(iob = 0.0, threshold = 0.0)
        assertThat(sut.shouldSuppressLowAlarm(lowAlarm())).isTrue()
    }

    @Test
    fun `shouldSuppressLowAlarm suppresses when IOB below threshold`() {
        mockIobGate(iob = -0.3, threshold = 0.0)
        assertThat(sut.shouldSuppressLowAlarm(lowAlarm())).isTrue()
    }

    @Test
    fun `shouldSuppressLowAlarm fires for urgent level low alarm`() {
        mockIobGate(iob = -1.0)
        assertThat(sut.shouldSuppressLowAlarm(lowAlarm(level = 2))).isFalse()
    }

    @Test
    fun `shouldSuppressLowAlarm fires when IOB calculation fails`() {
        whenever(preferences.get(BooleanKey.NsClientAlarmLowGateOnIob)).thenReturn(true)
        whenever(preferences.get(DoubleKey.NsClientAlarmLowIobThreshold)).thenReturn(0.0)
        whenever(iobCobCalculator.calculateIobFromBolus()).thenThrow(RuntimeException("no data"))
        assertThat(sut.shouldSuppressLowAlarm(lowAlarm())).isFalse()
    }
}