package com.yancotv.shared.backup

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * MK.19.8 — JSON serialization config used for both export-on-disk and
 * the checksum input. Single source of truth so the import-side
 * verification reads the file the same way the export wrote it.
 *
 * Key choices:
 *  - `prettyPrint = false` for the checksum payload so the SHA-256 is
 *    stable; pretty-printing is only used for the on-disk pass below.
 *  - `encodeDefaults = true` so optional fields land in the file even
 *    when set to defaults (otherwise `schemaVersion = 1` would be elided
 *    and a future schema bump couldn't tell v1 from v2 when v2's schema
 *    field defaults to 2).
 *  - `prettyPrint` for the on-disk file: human-readable, easier for the
 *    user to inspect or hand-edit if they need to. The checksum is
 *    computed against the compact form so this is purely cosmetic.
 *  - `ignoreUnknownKeys` on the import side so older or in-development
 *    fields don't fail the round-trip.
 */
object BackupCanonicalJson {
    /** Compact form — used for checksum input. */
    val Compact: Json =
        Json {
            prettyPrint = false
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

    /** Pretty-printed form — used for the on-disk export file. */
    val Pretty: Json =
        Json {
            prettyPrint = true
            prettyPrintIndent = "  "
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

    /** Encode a [BackupFileV1] as pretty-printed JSON string for on-disk storage. */
    fun encodePretty(file: BackupFileV1): String = Pretty.encodeToString(file)

    /** Encode an arbitrary serializable value as compact JSON. Used for
     *  the BackupMetadata.record_counts column. */
    inline fun <reified T> encodeCompact(value: T): String = Compact.encodeToString(value)

    /** Decode a backup file from JSON text. Tolerates both pretty and
     *  compact forms (whitespace is irrelevant to JSON parsing). */
    fun decodeBackupFile(text: String): BackupFileV1 = Compact.decodeFromString<BackupFileV1>(text)
}
