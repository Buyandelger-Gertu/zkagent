# -*- coding: utf-8 -*-
"""
WINDOWS ДЭЭР АЖИЛЛУУЛНА. ZKTeco Standalone SDK-гийн царай бүртгэх
командыг илрүүлэх зорилготой.

ЯАГААД ЭНЭ ХЭРЭГТЭЙ ВЭ
  xFace600 нь зургаас царайны загвар гаргаж чадна (BioPhotoFun=1), гэхдээ
  тэр командыг ZKTeco нийтлээгүй. 4370 протоколын нээлттэй задлалт ч
  хамраагүй (`SetUserFace` = «тодорхойлогдоогүй»).

  Тиймээс SDK-г НЭГ УДАА ажиллуулж, сүлжээн дээр юу явахыг барьж авна.
  Тэрнээс цааш Android агент дээр өөрсдөө хэрэгжүүлнэ — Windows хэрэггүй
  болно.

УРЬДЧИЛСАН НӨХЦӨЛ
  1. ZKTeco Standalone SDK суулгасан (zkemkeeper.dll бүртгэгдсэн)
  2. pip install pywin32
  3. Wireshark АСААЛТТАЙ, шүүлтүүр:  tcp.port == 4370
  4. Энэ компьютер терминалтай нэг сүлжээнд

АЖИЛЛУУЛАХ
  python probe_face_sdk.py 192.168.1.104 zurag.jpg 100
      (100 = туршилтын PIN. БАЙГАА хүний PIN-ийг битгий бич.)
"""

import base64
import datetime
import io
import os
import struct
import sys

# ── Бүх гаралтыг файлд ХАМТ бичнэ — дараа нь надад илгээхэд хялбар ──────
class Tee:
    def __init__(self, path):
        self.f = io.open(path, "w", encoding="utf-8")
    def write(self, s):
        sys.__stdout__.write(s); self.f.write(s); self.f.flush()
    def flush(self):
        sys.__stdout__.flush(); self.f.flush()

LOG = "probe_face_sdk_log.txt"
sys.stdout = Tee(LOG)

print(f"# {datetime.datetime.now():%Y-%m-%d %H:%M:%S}")

# ── ⚠️ 32 бит эсэхийг ЭХЛЭЭД шалгана ───────────────────────────────────
bits = struct.calcsize("P") * 8
print(f"# Python {sys.version.split()[0]} · {bits} бит")
if bits != 32:
    print()
    print("  ✗ 64 битийн Python байна.")
    print("    zkemkeeper.dll нь 32 БИТИЙН COM бүрэлдэхүүн — 64 битээс")
    print("    дуудвал 'Class not registered' гэж унана.")
    print("    python.org-оос Windows installer (32-bit) татаж суулгана уу.")
    sys.exit(1)

try:
    import win32com.client
except ImportError:
    sys.exit("pywin32 алга:  pip install pywin32")

HOST = sys.argv[1] if len(sys.argv) > 1 else "192.168.1.104"
PHOTO = sys.argv[2] if len(sys.argv) > 2 else "zurag.jpg"
PIN = sys.argv[3] if len(sys.argv) > 3 else "100"
PORT = 4370
COMMKEY = 123456

# Харагдах гэрлийн төхөөрөмжид царайны индекс 50 нь «BioPhoto» гэсэн
# утгатай гэж баримтууд заадаг. Батлагдаагүй тул бусдыг нь ч туршина.
FACE_INDEXES = [50, 0, 1]


def line(ch="─"):
    print(ch * 62)


def main():
    if not os.path.exists(PHOTO):
        sys.exit(f"зураг олдсонгүй: {PHOTO}")

    try:
        axz = win32com.client.Dispatch("zkemkeeper.ZKEM")
    except Exception as e:
        print(f"\n  ✗ zkemkeeper COM ачаалагдсангүй: {e}\n")
        print("    Магадгүй DLL бүртгэгдээгүй. SDK-гийн хавтаст очоод,")
        print("    АДМИН эрхтэй cmd дээр:")
        print("        regsvr32 zkemkeeper.dll")
        sys.exit(1)

    line()
    print(f"  {HOST}:{PORT} руу холбогдож байна…")
    if not axz.Connect_Net(HOST, PORT):
        sys.exit(f"  ✗ холбогдсонгүй (алдаа {axz.GetLastError()})")
    print("  ✓ холбогдлоо")

    try:
        axz.EnableDevice(1, False)          # бүртгэлийн үед хүн цохихоос сэргийлнэ

        # ── SDK ямар царайны функцтэйг харуулна ─────────────────────────
        line()
        print("  SDK дээр байгаа царай/зурагтай холбоотой функцууд:")
        found = []
        for name in sorted(dir(axz)):
            low = name.lower()
            if any(k in low for k in ("face", "photo", "bio", "pic", "image")):
                found.append(name)
                print(f"    · {name}")
        if not found:
            print("    (олдсонгүй — SDK хувилбар хуучин байж магадгүй)")

        with io.open("sdk_methods.txt", "w", encoding="utf-8") as fh:
            fh.write("\n".join(sorted(dir(axz))))
        print("    → бүх функцийн жагсаалт: sdk_methods.txt")

        # ── Хэрэглэгч үүсгэнэ ───────────────────────────────────────────
        line()
        print(f"  PIN={PIN} хэрэглэгч үүсгэж байна…")
        ok = axz.SSR_SetUserInfo(1, PIN, "PhotoTest", "", 0, True)
        print(f"    {'✓ үүслээ' if ok else '✗ болсонгүй (алдаа %d)' % axz.GetLastError()}")

        # ── Зургийг base64 болгоно ──────────────────────────────────────
        raw = open(PHOTO, "rb").read()
        b64 = base64.b64encode(raw).decode("ascii")
        print(f"  зураг: {len(raw)} байт → base64 {len(b64)} тэмдэгт")

        # ── ⚠️ ЭНЭ БОЛ ГОЛ МӨЧ. Wireshark энд юу явахыг барина. ─────────
        line()
        print("  SetUserFaceStr дуудаж байна — Wireshark одоо бичиж байх ёстой")
        for idx in FACE_INDEXES:
            try:
                ok = axz.SetUserFaceStr(1, PIN, idx, b64, len(b64))
                err = axz.GetLastError()
                print(f"    FaceIndex={idx:<3} → {'✓ АМЖИЛТТАЙ' if ok else f'✗ алдаа {err}'}")
                if ok:
                    print("\n  🎉 Зургаар бүртгэлээ. Wireshark-аа зогсоо, .pcapng хадгал.")
                    break
            except Exception as e:
                print(f"    FaceIndex={idx:<3} → дуудлага амжилтгүй: {e}")

        axz.RefreshData(1)

    finally:
        axz.EnableDevice(1, True)           # ⚠️ ЗААВАЛ буцааж асаана
        axz.Disconnect()
        line()
        print("  салгалаа, төхөөрөмж хэвийн горимд буцлаа")
        print(f"\n  Надад илгээх файлууд:")
        print(f"    1. {LOG}")
        print(f"    2. sdk_methods.txt")
        print(f"    3. Wireshark-ийн .pcapng")


if __name__ == "__main__":
    main()
