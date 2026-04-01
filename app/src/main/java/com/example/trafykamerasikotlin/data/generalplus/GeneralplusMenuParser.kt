package com.example.trafykamerasikotlin.data.generalplus

import android.util.Log
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStreamReader

/**
 * Parses the Menu.xml returned by a GeneralPlus dashcam (GetParameterFile command).
 *
 * XML structure:
 *   <Menu version="1.0">
 *     <Categories>
 *       <Category>
 *         <Name>系统设置</Name>
 *         <Settings>
 *           <Setting>
 *             <Name>分辨率</Name>
 *             <ID>0x0000</ID>
 *             <Type>0x00</Type>
 *             <Default>0x00</Default>
 *             <Values>
 *               <Value><Name>1080P 30fps</Name><ID>0x00</ID></Value>
 *               <Value><Name>720P 60fps</Name><ID>0x01</ID></Value>
 *             </Values>
 *           </Setting>
 *         </Settings>
 *       </Category>
 *     </Categories>
 *   </Menu>
 *
 * IDs are hex strings like "0x0000"; parsed with [parseHexId].
 * Names may be Chinese; callers should pass them through GeneralplusTranslations.
 */
object GeneralplusMenuParser {

    private const val TAG = "Trafy.GPParser"

    /** A single dashcam setting parsed from the camera's Menu.xml. */
    data class GpSetting(
        val id: Int,
        val name: String,
        /** 0=enum, 1=action, 2=string — from the XML <Type> element. */
        val type: Int,
        val defaultValueId: Int,
        val values: List<GpValue>,
    )

    /** A selectable value within a setting. */
    data class GpValue(
        val id: Int,
        val name: String,
    )

    /**
     * Parses [xmlBytes] (UTF-8) and returns all settings found.
     * Returns an empty list on parse error.
     */
    fun parse(xmlBytes: ByteArray): List<GpSetting> {
        val result = mutableListOf<GpSetting>()
        try {
            val parser = Xml.newPullParser()
            parser.setInput(InputStreamReader(xmlBytes.inputStream(), Charsets.UTF_8))

            // Per-setting accumulators
            var inSetting   = false
            var inValues    = false
            var inValue     = false
            var currentText = ""

            var settingId   = -1
            var settingName = ""
            var settingType = 0
            var defaultId   = 0
            val values      = mutableListOf<GpValue>()

            // Per-value accumulators
            var valueId   = -1
            var valueName = ""

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        currentText = ""
                        when (parser.name) {
                            "Setting" -> {
                                settingId   = -1
                                settingName = ""
                                settingType = 0
                                defaultId   = 0
                                values.clear()
                                inSetting   = true
                            }
                            "Values" -> if (inSetting) inValues = true
                            "Value"  -> if (inValues) {
                                valueId   = -1
                                valueName = ""
                                inValue   = true
                            }
                        }
                    }
                    XmlPullParser.TEXT -> currentText += parser.text

                    XmlPullParser.END_TAG -> {
                        when (parser.name) {
                            "ID" -> {
                                val parsed = parseHexId(currentText)
                                when {
                                    inValue   -> valueId   = parsed
                                    inSetting -> settingId = parsed
                                }
                            }
                            "Name" -> {
                                val trimmed = currentText.trim()
                                when {
                                    inValue   -> valueName = trimmed
                                    inSetting -> settingName = trimmed
                                }
                            }
                            "Type" -> if (inSetting && !inValues) {
                                settingType = parseHexId(currentText)
                            }
                            "Default" -> if (inSetting && !inValues) {
                                defaultId = parseHexId(currentText)
                            }
                            "Value" -> if (inValue) {
                                if (valueId >= 0) values.add(GpValue(valueId, valueName))
                                inValue = false
                            }
                            "Values" -> inValues = false
                            "Setting" -> {
                                if (inSetting && settingId >= 0) {
                                    result.add(GpSetting(settingId, settingName, settingType, defaultId, values.toList()))
                                }
                                inSetting = false
                            }
                        }
                        currentText = ""
                    }
                }
                event = parser.next()
            }
        } catch (e: Exception) {
            Log.e(TAG, "parse error: ${e.message}")
        }
        Log.d(TAG, "parsed ${result.size} settings")
        return result
    }

    private fun parseHexId(s: String): Int = try {
        s.trim().removePrefix("0x").toInt(16)
    } catch (_: NumberFormatException) {
        -1
    }
}
