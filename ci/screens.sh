#!/usr/bin/env bash
# Abre Nioro Launcher en un Android virtual (Pixel 5, 1080x2340), recorre las pantallas y guarda capturas.
set +x
P=com.nioro.launcher
mkdir -p shots
adb install -r app/build/outputs/apk/debug/app-debug.apk > shots/install.txt 2>&1
adb shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER > acts.txt 2>&1
adb logcat -c

shot() { sleep "${2:-3}"; timeout 30 adb exec-out screencap -p > "shots/$1.png"; echo "captura $1"; }
prefs() {
  timeout 20 adb shell am force-stop $P
  python3 ci/mkprefs.py acts.txt "$1" prefs.xml
  timeout 30 adb push prefs.xml /data/local/tmp/nl.xml > /dev/null 2>&1
  timeout 30 adb shell "chmod 644 /data/local/tmp/nl.xml; run-as $P mkdir -p shared_prefs; run-as $P cp /data/local/tmp/nl.xml shared_prefs/nioro_launcher.xml"
}
start() { timeout 60 adb shell am start -W -n $P/.MainActivity > /dev/null 2>&1; sleep 8; }
back() { timeout 20 adb shell input keyevent 4; sleep 2; }
swipe_up() { timeout 20 adb shell input swipe 540 1750 540 700 260; }
hold() { timeout 20 adb shell input swipe "$1" "$2" "$1" "$2" 1300; }

{
  # 1) inicio con iconos y dock
  prefs 0; start; shot 01_inicio
  # 2) modo edición: mantener pulsado un hueco del fondo
  hold 540 1750; shot 02_edicion; back; sleep 2
  # 3) menú del reloj: mantener pulsado el reloj
  hold 540 300; shot 03_reloj_menu 4; back; sleep 2
  # 4) las 5 vistas de apps (deslizar hacia arriba)
  swipe_up; shot 04_launchpad; back
  prefs 2; start; swipe_up; shot 05_rejilla
  timeout 20 adb shell input swipe 540 1500 540 1100 400; shot 05b_rejilla_scroll 1; back
  prefs 3; start; swipe_up; shot 06_lista; back
  prefs 1; start; swipe_up; shot 07_abanico; back
  prefs 4; start; swipe_up; shot 08_gravedad 5; back
  # 5) menú contextual de un icono del inicio (mantener pulsado el primero)
  prefs 0; start; hold 135 700; shot 09_menu_icono; back
  echo "== errores graves"
  adb logcat -d -b crash | tail -n 80
} > shots/report.txt 2>&1
cat shots/report.txt
