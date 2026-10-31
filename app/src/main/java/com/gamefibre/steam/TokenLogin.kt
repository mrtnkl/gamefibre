package com.gamefibre.steam

import java.util.Base64
import com.google.protobuf.ByteString
import `in`.dragonbra.javasteam.base.ClientMsgProtobuf
import `in`.dragonbra.javasteam.enums.EMsg
import `in`.dragonbra.javasteam.enums.EOSType
import `in`.dragonbra.javasteam.generated.MsgClientLogon
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesBase.CMsgIPAddress
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesClientserverLogin.CMsgClientLogon
import `in`.dragonbra.javasteam.util.HardwareUtils
import org.json.JSONObject

/** Read routing/expiry claims only. Steam verifies the token's signature when logging on. */
internal fun tokenSteamId(token: String, requireUnexpired: Boolean = true): Long {
    val parts = token.split('.')
    require(parts.size == 3) { "Invalid Steam session" }
    val claims = JSONObject(String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8))
    require(claims.getString("iss") == "steam") { "Invalid Steam session" }
    val audiences = claims.getJSONArray("aud")
    require((0 until audiences.length()).any { audiences.getString(it) == "client" }) { "Invalid Steam session" }
    require(!requireUnexpired || claims.getLong("exp") > System.currentTimeMillis() / 1000) { "Steam session expired" }
    return claims.getString("sub").toLong().also { require(it > 0) { "Invalid Steam session" } }
}

/** JavaSteam 1.8's logOn wrapper requires a username, although the token protocol does not.
 * Token-only protocol reference: https://github.com/DoctorMcKay/node-steam-user/blob/master/components/09-logon.js
 * Keep this packet aligned with JavaSteam's SteamUser.logOn, omitting all credential fields.
 */
internal fun tokenLogonMessage(token: String, cellId: Int): ClientMsgProtobuf<CMsgClientLogon.Builder> {
    val message = ClientMsgProtobuf<CMsgClientLogon.Builder>(CMsgClientLogon::class.java, EMsg.ClientLogon)
    message.protoHeader.setClientSessionid(0).setSteamid(tokenSteamId(token))
    message.body.apply {
        setAccessToken(token)
        setShouldRememberPassword(true)
        setProtocolVersion(MsgClientLogon.CurrentProtocol)
        setObfuscatedPrivateIp(CMsgIPAddress.newBuilder().setV4(0x53444c))
        setDeprecatedObfustucatedPrivateIp(0x53444c)
        setClientOsType(EOSType.WinUnknown.code())
        setClientLanguage("english")
        setCellId(cellId)
        setClientPackageVersion(1771)
        setSupportsRateLimitResponse(true)
        setMachineName("GameFibre · Android")
        setMachineId(ByteString.copyFrom(HardwareUtils.getMachineID()))
    }
    return message
}
