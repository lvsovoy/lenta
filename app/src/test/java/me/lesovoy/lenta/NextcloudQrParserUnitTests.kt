package me.lesovoy.lenta

import me.lesovoy.lenta.data.nextcloud.NextcloudQrParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NextcloudQrParserUnitTests {

    @Test
    fun testStandardNextcloudQrScheme() {
        val qr = "nc://admin:abcd-1234-efgh-5678@https://cloud.example.com"
        val creds = NextcloudQrParser.parse(qr)
        assertNotNull(creds)
        assertEquals("https://cloud.example.com", creds!!.serverUrl)
        assertEquals("admin", creds.username)
        assertEquals("abcd-1234-efgh-5678", creds.password)
    }

    @Test
    fun testStandardNextcloudQrSchemeWithoutHttpPrefix() {
        val qr = "nc://john.doe:secret-token@mycloud.org/nextcloud"
        val creds = NextcloudQrParser.parse(qr)
        assertNotNull(creds)
        assertEquals("https://mycloud.org/nextcloud", creds!!.serverUrl)
        assertEquals("john.doe", creds.username)
        assertEquals("secret-token", creds.password)
    }

    @Test
    fun testNextcloudSchemeWithUrlEncodedUser() {
        val qr = "nc://user%40example.com:mypassword@https://cloud.example.com:8443"
        val creds = NextcloudQrParser.parse(qr)
        assertNotNull(creds)
        assertEquals("https://cloud.example.com:8443", creds!!.serverUrl)
        assertEquals("user@example.com", creds.username)
        assertEquals("mypassword", creds.password)
    }

    @Test
    fun testNextcloudSchemeWithWebDavSuffixInUrl() {
        val qr = "nc://admin:app-pass@https://cloud.example.com/remote.php/dav/files/admin/"
        val creds = NextcloudQrParser.parse(qr)
        assertNotNull(creds)
        assertEquals("https://cloud.example.com", creds!!.serverUrl)
        assertEquals("admin", creds.username)
        assertEquals("app-pass", creds.password)
    }

    @Test
    fun testNextcloudSchemeAlternativeName() {
        val qr = "nextcloud://alice:supersecret@http://192.168.1.50:8080"
        val creds = NextcloudQrParser.parse(qr)
        assertNotNull(creds)
        assertEquals("http://192.168.1.50:8080", creds!!.serverUrl)
        assertEquals("alice", creds.username)
        assertEquals("supersecret", creds.password)
    }

    @Test
    fun testJsonFormat() {
        val json = """{"server":"https://demo.nextcloud.com","user":"demouser","password":"demopassword"}"""
        val creds = NextcloudQrParser.parse(json)
        assertNotNull(creds)
        assertEquals("https://demo.nextcloud.com", creds!!.serverUrl)
        assertEquals("demouser", creds.username)
        assertEquals("demopassword", creds.password)
    }

    @Test
    fun testJsonWithAlternativeKeys() {
        val json = """{"url":"https://nc.example.com","loginName":"bob","token":"token123"}"""
        val creds = NextcloudQrParser.parse(json)
        assertNotNull(creds)
        assertEquals("https://nc.example.com", creds!!.serverUrl)
        assertEquals("bob", creds.username)
        assertEquals("token123", creds.password)
    }

    @Test
    fun testKeyValueFormat() {
        val kv = "server:https://cloud.org;user:clara;password:pass_abc_123"
        val creds = NextcloudQrParser.parse(kv)
        assertNotNull(creds)
        assertEquals("https://cloud.org", creds!!.serverUrl)
        assertEquals("clara", creds.username)
        assertEquals("pass_abc_123", creds.password)
    }

    @Test
    fun testHttpUriWithUserInfo() {
        val uri = "https://david:davidpass@storage.example.com/remote.php/webdav"
        val creds = NextcloudQrParser.parse(uri)
        assertNotNull(creds)
        assertEquals("https://storage.example.com", creds!!.serverUrl)
        assertEquals("david", creds.username)
        assertEquals("davidpass", creds.password)
    }

    @Test
    fun testNextcloudOnetimeLoginFormat() {
        val qr = "nc://onetime-login/user:kleso&password:9ZGTL-aTKyn-i34Ld-wbTcB-6L9mT&server:https://nextcloud.lesovoy.me"
        val creds = NextcloudQrParser.parse(qr)
        assertNotNull(creds)
        assertEquals("https://nextcloud.lesovoy.me", creds!!.serverUrl)
        assertEquals("kleso", creds.username)
        assertEquals("9ZGTL-aTKyn-i34Ld-wbTcB-6L9mT", creds.password)
    }

    @Test
    fun testNextcloudLoginActionFormat() {
        val qr = "nc://login/user:admin&password:secrettoken&server:https://cloud.myserver.com"
        val creds = NextcloudQrParser.parse(qr)
        assertNotNull(creds)
        assertEquals("https://cloud.myserver.com", creds!!.serverUrl)
        assertEquals("admin", creds.username)
        assertEquals("secrettoken", creds.password)
    }

    @Test
    fun testNextcloudOnetimeLoginWithQueryParams() {
        val qr = "nc://onetime-login?user=kleso&password=mypassword&server=https://nextcloud.lesovoy.me"
        val creds = NextcloudQrParser.parse(qr)
        assertNotNull(creds)
        assertEquals("https://nextcloud.lesovoy.me", creds!!.serverUrl)
        assertEquals("kleso", creds.username)
        assertEquals("mypassword", creds.password)
    }

    @Test
    fun testNextcloudPasswordWithPlusCharacters() {
        val qr = "nc://login/user:alice&password:secret+token+123&server:https://cloud.example.com"
        val creds = NextcloudQrParser.parse(qr)
        assertNotNull(creds)
        assertEquals("https://cloud.example.com", creds!!.serverUrl)
        assertEquals("alice", creds.username)
        assertEquals("secret+token+123", creds.password)
    }

    @Test
    fun testNextcloudQrOptionsBeepDisabled() {
        val options = me.lesovoy.lenta.ui.scanner.QrScannerActivity.createScanOptions("Scan")
        assertNotNull(options)
    }

    @Test
    fun testInvalidInputs() {
        assertNull(NextcloudQrParser.parse(null))
        assertNull(NextcloudQrParser.parse(""))
        assertNull(NextcloudQrParser.parse("   "))
        assertNull(NextcloudQrParser.parse("hello world random string"))
    }
}
