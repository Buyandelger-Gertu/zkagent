package mn.gertu.zkagent.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import mn.gertu.zkagent.data.Settings

/**
 * Утас restart хийсний дараа агентыг автоматаар асаана.
 *
 * Үүнгүйгээр цахилгаан тасраад ирэхэд хэн нэгэн гараар нээх хүртэл
 * лог цуглуулахаа болино — байранд байгаа төхөөрөмж дээр тэр нь
 * хэдэн өдөр үргэлжилж мэднэ.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) return

        val settings = Settings(context)
        if (!settings.autoStart) {
            Log.i("ZkAgent", "boot: autoStart унтраалттай")
            return
        }
        if (!settings.isComplete()) {
            Log.i("ZkAgent", "boot: тохиргоо дутуу, эхлүүлэхгүй")
            return
        }

        Log.i("ZkAgent", "boot: агентыг эхлүүлж байна")
        AgentService.start(context)
    }
}
