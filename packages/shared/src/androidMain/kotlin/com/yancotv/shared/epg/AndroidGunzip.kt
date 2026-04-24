package com.yancotv.shared.epg

import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

/**
 * Android gunzip for `.xml.gz` EPG dumps whose server does NOT advertise
 * `Content-Encoding: gzip`. Injected into [EpgRepository] via DI.
 */
fun androidGunzip(bytes: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
