# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Что это

Инструмент для изучения CAN-шин PSA 125 кбит/с (Citroën C4 2008): Arduino Nano + MCP2515 и Android-приложение-пульт. Адаптер работает в одном из трёх режимов, который выбирается из приложения: `IDLE` (молчит), `SNIFF` (слушает настоящую шину), `BENCH` (изображает BSI, магнитолу и кнопки панели для штатного дисплея на столе).

- `firmware/PsaCanLab/` — прошивка, один скетч.
- `android/` — приложение.
- `docs/PROTOCOL.md` — протокол обмена и кадры стенда.
- `legacy/` — исходники заменённых репозиториев `CanFrameLog`, `EmulateBSIPSA`, `TestPSAScreen`; не собираются, не править.

## Команды

```
arduino-cli compile --warnings all --fqbn arduino:avr:nano firmware/PsaCanLab

cd android
./gradlew assembleDebug            # нужен JDK 17 (jbr из Android Studio) и local.properties с sdk.dir
./gradlew testDebugUnitTest        # все тесты
./gradlew testDebugUnitTest --tests "com.vantserg.psacanlab.can.FrameTableTest"
./gradlew lintDebug
```

## Что важно знать

- **Безопасность шины — главное ограничение.** Прошивка передаёт только в `BENCH`. Любая смена режима начинается с `setListenOnlyMode()`, а вход в `BENCH` запрещён, если за `BSI_CHECK_MS` на шине найден кадр `0x036` или `0x0F6` (функция `busHasBsi`). Новые функции, передающие в шину, должны оставаться за этой проверкой.
- **Протокол продублирован в трёх местах:** `docs/PROTOCOL.md`, прошивка (`processLine`, `printState`, таблица `panelButtons`) и приложение (`MainActivity`: имена команд в `buildBenchControls`, `PANEL_BUTTONS`, разбор `STATE` в `syncBench`). Менять вместе.
- **Скорость порта** 500 000 бод задана в двух местах: `SERIAL_BAUD` в прошивке и `BAUD_RATE` в `LabLink.kt`.
- **Строка кадра** `<ID>\t<DLC>\t<B0>…` (HEX без ведущих нулей) — формат прежней библиотеки `CanFrameLog`; его же, с префиксом `CAN `, использует прошивка `PSA-CAN-adapter`.
- **Потоки в приложении:** `LabLink` вызывает слушателя из потока связи. Кадры идут прямо в потокобезопасную `FrameTable`, текстовые строки переносятся в главный поток. Экран перерисовывает таблицу по таймеру раз в 200 мс, а не на каждый кадр.
- **Элементы стенда создаются из кода** (`buildBenchControls`), а не в разметке; флаг `syncing` не даёт отправлять команды, пока элементы обновляются по строке `STATE`.
- Пакет `can/` — чистый Kotlin без зависимостей от Android, тесты покрывают только его.
- `KnownFrames` — подсказки для таблицы, собранные из собственных проверок и открытых баз PSA-RE и autowp; часть не проверена на автомобиле.
- Словарь `InfoMessages` скопирован из приложения `PSADisplay`; при пополнении обновлять в обоих.
