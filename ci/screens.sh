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
  python3 ci/mkprefs.py acts.txt "$1" prefs.xml $2
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
  # teclado abierto: los resultados deben quedar por encima del teclado
  adb logcat -c
  prefs 2; start; swipe_up; shot 10a_antes 2; timeout 20 adb shell input tap 540 255; shot 10b_tras_tocar 1; timeout 20 adb shell input text c; shot 10_rejilla_teclado 3
  echo "== diagnóstico teclado"; adb shell dumpsys input_method | grep -E "mInputShown|mShowRequested|mCurFocusedWindow" | head -5
  adb logcat -d -s NioroDbg:W AndroidRuntime:E | tail -n 60
  back; back
  prefs 0; start; swipe_up; sleep 2; timeout 20 adb shell input tap 540 255; sleep 2; shot 11_launchpad_teclado 3; back; back
  # 6) vídeos de las animaciones: cada uno se convierte en una tira de fotogramas (10 por segundo)
  rec() {
    n=$1; shift
    timeout 25 adb shell "screenrecord --time-limit 3 --size 540x1170 --bit-rate 6000000 /sdcard/$n.mp4" &
    RP=$!; sleep 1; "$@"; wait $RP; sleep 1
    timeout 30 adb pull /sdcard/$n.mp4 shots/$n.mp4 > /dev/null 2>&1
    ffmpeg -loglevel error -y -i shots/$n.mp4 -vf "fps=10,scale=180:-1,tile=10x3" -frames:v 1 "shots/$n.png" && echo "video $n"
  }
  g_paginas() { timeout 20 adb shell input swipe 900 1300 150 1300 260; sleep 1.1; timeout 20 adb shell input swipe 150 1300 900 1300 260; }
  g_abrir_lento() { timeout 20 adb shell input swipe 540 1750 540 950 900; }
  g_cerrar_tirando() { timeout 20 adb shell input swipe 540 700 540 1650 420; }
  g_tocar_fuera() { timeout 20 adb shell input tap 540 2050; }
  g_lista_fling() { timeout 20 adb shell input swipe 540 1700 540 700 140; }
  g_gota() { timeout 20 adb shell input tap 540 255; }
  prefs 0 paginas; start; rec v1_paginas_inicio g_paginas
  rec v2_abrir_cajon_dedo g_abrir_lento
  rec v3_cerrar_tirando g_cerrar_tirando
  prefs 2; start; swipe_up; sleep 2; rec v4_rejilla_cerrar g_tocar_fuera
  prefs 3; start; swipe_up; sleep 2; rec v5_lista_fling g_lista_fling; back
  prefs 2; start; swipe_up; sleep 2; rec v6_gota_busqueda g_gota; back; back

  # 7) cajón a mitad de gesto: el dedo se queda quieto a media subida
  prefs 0; start
  # (un deslizamiento lento de 3 s en segundo plano; la captura se hace con el dedo aún abajo)
  timeout 20 adb shell input swipe 540 1700 540 1000 3000 &
  SW=$!; sleep 1.8; timeout 20 adb exec-out screencap -p > shots/12_cajon_a_mitad.png; echo "captura 12_cajon_a_mitad"
  wait $SW; sleep 2; back
  # 5) menú contextual de un icono del inicio (mantener pulsado el primero)
  prefs 0; start; hold 135 700; shot 09_menu_icono; back
  echo "== errores graves"
  adb logcat -d -b crash | tail -n 80
} > shots/report.txt 2>&1
cat shots/report.txt
