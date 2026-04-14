package com.example.trafykamerasikotlin.data.settings

import com.example.trafykamerasikotlin.data.model.SettingOption

/**
 * Labels and option sets for the Allwinner V853 (A19) settings surfaced in the UI.
 *
 * The device exposes a single `relay:getsettings` blob with many flat keys; only the
 * user-facing ones listed in [SPEC] are mapped. Anything not listed here is hidden.
 *
 * Writability: `writable = false` means the setting is shown read-only in Stages 1–2
 * and will be enabled once Stage 3 (`setsettings` canary with `volume`) is verified
 * on-device.
 */
internal object AllwinnerTranslations {

    data class Spec(
        val key: String,
        val title: String,
        val options: List<SettingOption>,
        val writable: Boolean,
        val writeCmd: String? = null,
        val writeParam: String? = null,
    )

    private fun onOff() = listOf(
        SettingOption("0", "Kapalı"),
        SettingOption("1", "Açık"),
    )

    val SPEC: List<Spec> = listOf(
        Spec(
            key = "volume",
            title = "Ses Seviyesi",
            options = listOf(
                SettingOption("0", "Sessiz"),
                SettingOption("1", "Düşük"),
                SettingOption("2", "Orta"),
                SettingOption("3", "Yüksek"),
            ),
            writable = true,
            writeCmd = "setvol",
            writeParam = "volume",
        ),
        Spec(
            key = "accnotify",
            title = "Çarpma Uyarısı",
            options = onOff(),
            writable = true,
            writeCmd = "setaccnotify",
            writeParam = "accnotify",
        ),
        Spec(
            key = "gpsosd",
            title = "GPS OSD",
            options = onOff(),
            writable = true,
            writeCmd = "gpsosd",
            writeParam = "enable",
        ),
        Spec(
            key = "muterec",
            title = "Kayıt Sırasında Mikrofon",
            options = listOf(
                SettingOption("0", "Açık"),
                SettingOption("1", "Kapalı"),
            ),
            writable = true,
            writeCmd = "muterec",
            writeParam = "enable",
        ),
        Spec(
            key = "colsense",
            title = "Çarpma Hassasiyeti",
            options = listOf(
                SettingOption("1", "Düşük"),
                SettingOption("2", "Orta"),
                SettingOption("3", "Yüksek"),
            ),
            writable = true,
            writeCmd = "setcolsense",
            writeParam = "sense",
        ),
        Spec(
            key = "colwake",
            title = "Çarpmada Uyan",
            options = onOff(),
            writable = true,
            writeCmd = "colwake",
            writeParam = "enable",
        ),
        Spec(
            key = "colcapture",
            title = "Çarpmada Fotoğraf Çek",
            options = onOff(),
            writable = true,
            writeCmd = "colcapture",
            writeParam = "enable",
        ),
        Spec(
            key = "report",
            title = "Konum Raporlama",
            options = onOff(),
            writable = true,
            writeCmd = "setreport",
            writeParam = "report",
        ),
        Spec(
            key = "slowrectime",
            title = "Yavaş Kayıt",
            options = listOf(
                SettingOption("0", "Kapalı"),
                SettingOption("1", "Açık (1 dk)"),
                SettingOption("2", "Açık (3 dk)"),
            ),
            writable = true,
            writeCmd = "setslowrec",
            writeParam = "slowrectime",
        ),
        Spec(
            key = "bcamflip",
            title = "Arka Kamera Ters Çevir",
            options = onOff(),
            writable = true,
            writeCmd = "bcamflip",
            writeParam = "enable",
        ),
        Spec(
            key = "fres",
            title = "Kayıt Çözünürlüğü",
            options = listOf(
                SettingOption("1080P", "1080P"),
                SettingOption("1440P", "2K / 1440P"),
            ),
            writable = true,
            writeCmd = "setres",
            writeParam = "resolution",
        ),
        Spec(
            key = "liveres",
            title = "Canlı Yayın Çözünürlüğü",
            options = listOf(
                SettingOption("0", "1080P"),
                SettingOption("1", "1440P"),
            ),
            writable = false,
        ),
        Spec(
            key = "scroff",
            title = "Ekran Uyku (sn)",
            options = listOf(
                SettingOption("0", "Asla"),
                SettingOption("30", "30 sn"),
                SettingOption("60", "1 dk"),
                SettingOption("180", "3 dk"),
            ),
            writable = false,
        ),
        Spec(
            key = "livingsec",
            title = "Canlı Yayın Süresi (sn)",
            options = listOf(
                SettingOption("60", "1 dk"),
                SettingOption("180", "3 dk"),
                SettingOption("300", "5 dk"),
                SettingOption("600", "10 dk"),
            ),
            writable = false,
        ),
    )

    fun findSpec(key: String): Spec? = SPEC.firstOrNull { it.key == key }

    /** Displayed read-only rows for informational fields (no options, no writes). */
    data class InfoField(val key: String, val title: String)

    val INFO: List<InfoField> = listOf(
        InfoField("fwver",    "Yazılım Sürümü"),   // 180 → "1.8.0" (formatted in repo)
        InfoField("mdver",    "CAT1 Sürümü"),
        InfoField("imei",     "IMEI"),
        InfoField("iccid",    "ICCID"),
        InfoField("operator", "Operatör"),
        InfoField("rssi",     "Sinyal (RSSI seviye)"),
        InfoField("rsrp",     "RSRP (dBm)"),
        InfoField("rsrq",     "RSRQ (dB)"),
        InfoField("band",     "LTE Bandı"),
    )
}
