// PSA CAN Lab — прошивка Arduino Nano + MCP2515 для работы с комфортной/кузовной
// шиной PSA (125 кбит/с). Режим выбирается из Android-приложения по USB:
//
//   IDLE  — модуль молчит, шина не читается;
//   SNIFF — прослушивание настоящей шины: все кадры уходят в USB, в шину ничего
//           не передаётся (режим «только слушать», без подтверждения кадров);
//   BENCH — стенд: к модулю подключён только штатный дисплей, прошивка изображает
//           BSI, магнитолу и кнопки панели.
//
// Передача в шину возможна только в режиме BENCH. При входе в него прошивка
// сначала слушает шину и отказывается включаться, если видит кадры настоящего BSI.
//
// Протокол порта описан в docs/PROTOCOL.md.

#include <SPI.h>
#include <mcp2515.h>  // библиотека autowp-mcp2515

// ---- Железо ----
#define CAN_CS_PIN 10
#define CAN_CLOCK MCP_8MHZ
#define CAN_SPEED CAN_125KBPS
#define SERIAL_BAUD 500000UL

#define FIRMWARE_NAME "PSA-CAN-LAB"
#define FIRMWARE_VERSION "1.0"

// Сколько слушать шину перед включением стенда, мс
#define BSI_CHECK_MS 400
// Сколько кадров держать нажатие кнопки панели и признак «скрыть сообщение»
#define BUTTON_FRAMES 2
#define HIDE_FRAMES 10
// Сколько кадров 0x1A5 показывать всплывающую громкость после её изменения
#define VOLUME_POPUP_FRAMES 4
// Перебор сообщений: показ и пауза, мс
#define CYCLE_SHOW_MS 4000UL
#define CYCLE_PERIOD_MS 5000UL

// ---- Идентификаторы кадров ----
#define ID_BSI_POWER 0x036     // режим питания, яркость подсветки
#define ID_BSI_STATUS 0x0F6    // зажигание, температуры, задний ход
#define ID_BSI_MESSAGE 0x1A1   // сообщение бортового компьютера
#define ID_BSI_TIME 0x276      // дата и время
#define ID_RADIO_STATE 0x165
#define ID_RADIO_VOLUME 0x1A5
#define ID_RADIO_SETTINGS 0x1E5
#define ID_RADIO_PRESENCE 0x39B
#define ID_RADIO_INIT 0x5E0
#define ID_PANEL_BUTTONS 0x3E5

enum Mode : uint8_t { MODE_IDLE, MODE_SNIFF, MODE_BENCH };

// Кнопка панели магнитолы: имя, номер байта кадра 0x3E5 и маска
struct PanelButton
{
  const char* name;
  uint8_t index;
  uint8_t mask;
};

// MENU…RIGHT проверены на стенде с дисплеем; TEL, NAV, CLIM, TRIP, AUDIO взяты
// из открытой базы PSA-RE и на дисплее не проверялись
const PanelButton panelButtons[] = {
  { "MENU", 0, 0x40 }, { "MODE", 1, 0x10 }, { "OK", 2, 0x40 }, { "ESC", 2, 0x10 },
  { "DARK", 2, 0x04 }, { "UP", 5, 0x40 }, { "DOWN", 5, 0x10 }, { "LEFT", 5, 0x01 },
  { "RIGHT", 5, 0x04 }, { "TEL", 0, 0x10 }, { "NAV", 0, 0x04 }, { "CLIM", 0, 0x01 },
  { "TRIP", 1, 0x40 }, { "AUDIO", 1, 0x01 },
};
const uint8_t panelButtonCount = sizeof(panelButtons) / sizeof(panelButtons[0]);

MCP2515 mcp(CAN_CS_PIN);
Mode mode = MODE_IDLE;

// ---- Состояние стенда ----
bool ignition = true;
bool reverseGear = false;
bool radioOn = true;
bool darkMode = false;
bool cycleMessages = false;
int8_t outsideTemp = 10;    // °C
int16_t coolantTemp = 80;   // °C
uint8_t brightness = 15;    // 0–15
uint8_t volume = 15;        // 0–30
int16_t message = -1;       // код сообщения, -1 — нет
int16_t lastMessage = 0;    // последний показанный код: нужен для кадра «скрыть»
uint8_t hideFrames = 0;
uint8_t volumePopupFrames = 0;
int8_t pressedButton = -1;
uint8_t buttonFrames = 0;
bool radioInitSent = false;
unsigned long cycleStarted = 0;

unsigned long last100 = 0;
unsigned long last500 = 0;
unsigned long lostFrames = 0;
unsigned long lastLostReport = 0;

char line[48];
uint8_t lineLen = 0;

// ---------------------------------------------------------------------------
// Вывод
// ---------------------------------------------------------------------------

inline char hexDigit(uint8_t value)
{
  return value < 10 ? '0' + value : 'A' + value - 10;
}

// Кадр одной строкой: <ID>\t<DLC>\t<B0>\t<B1>… (HEX без ведущих нулей) — формат CanFrameLog
void printFrame(const struct can_frame& frame)
{
  char buf[40];
  uint8_t n = 0;
  uint16_t id = frame.can_id & 0x7FF;
  if (id > 0xFF) buf[n++] = hexDigit(id >> 8);
  if (id > 0x0F) buf[n++] = hexDigit((id >> 4) & 0x0F);
  buf[n++] = hexDigit(id & 0x0F);
  buf[n++] = '\t';
  buf[n++] = hexDigit(frame.can_dlc & 0x0F);
  for (uint8_t i = 0; i < frame.can_dlc && i < 8; i++)
  {
    buf[n++] = '\t';
    if (frame.data[i] > 0x0F) buf[n++] = hexDigit(frame.data[i] >> 4);
    buf[n++] = hexDigit(frame.data[i] & 0x0F);
  }
  buf[n++] = '\r';
  buf[n++] = '\n';
  Serial.write(buf, n);
}

void printMode()
{
  Serial.print(F("MODE "));
  Serial.println(mode == MODE_SNIFF ? F("SNIFF") : mode == MODE_BENCH ? F("BENCH") : F("IDLE"));
}

void printState()
{
  Serial.print(F("STATE IGN="));
  Serial.print(ignition);
  Serial.print(F(" REV="));
  Serial.print(reverseGear);
  Serial.print(F(" RADIO="));
  Serial.print(radioOn);
  Serial.print(F(" DARK="));
  Serial.print(darkMode);
  Serial.print(F(" CYCLE="));
  Serial.print(cycleMessages);
  Serial.print(F(" TEMP="));
  Serial.print(outsideTemp);
  Serial.print(F(" COOL="));
  Serial.print(coolantTemp);
  Serial.print(F(" BRIGHT="));
  Serial.print(brightness);
  Serial.print(F(" VOL="));
  Serial.print(volume);
  Serial.print(F(" MSG="));
  Serial.println(message);
}

void printHello()
{
  Serial.println(F("LAB " FIRMWARE_NAME " " FIRMWARE_VERSION));
  printMode();
  printState();
}

// ---------------------------------------------------------------------------
// Передача кадров стенда
// ---------------------------------------------------------------------------

void sendFrame(uint16_t id, uint8_t dlc, const uint8_t* data)
{
  struct can_frame frame;
  frame.can_id = id;
  frame.can_dlc = dlc;
  memcpy(frame.data, data, dlc);
  mcp.sendMessage(&frame);
}

void sendBsiPower()
{
  // Байт 3: 0x20 — ночной режим (яркость действует), 0x10 — тёмная панель, младшие 4 бита — яркость.
  // Байт 4: младшие 3 бита — 1 зажигание включено, 2 выключено.
  uint8_t data[8] = { 0x0E, 0x00, 0x05, 0x20, 0x20, 0x80, 0x00, 0xA0 };
  data[3] |= (brightness & 0x0F) | (darkMode ? 0x10 : 0x00);
  data[4] |= ignition ? 0x01 : 0x02;
  sendFrame(ID_BSI_POWER, 8, data);
}

void sendBsiStatus()
{
  // Байт 0: 0x08 — зажигание. Байт 1: температура ОЖ + 40. Байты 2–4: одометр.
  // Байт 6: наружная температура, (t + 40) * 2. Байт 7: 0x80 — задний ход.
  uint8_t data[8] = { 0x00, 0x00, 0x00, 0x1F, 0x00, 0x0D, 0x00, 0x01 };
  data[0] = ignition ? 0x08 : 0x00;
  data[1] = (uint8_t)constrain(coolantTemp + 40, 0, 255);
  data[6] = (uint8_t)constrain((outsideTemp + 40) * 2, 0, 255);
  if (reverseGear) data[7] |= 0x80;
  sendFrame(ID_BSI_STATUS, 8, data);
}

void sendBsiTime()
{
  // Значение, записанное с автомобиля; раскладка полей не подтверждена
  const uint8_t data[7] = { 0x95, 0x03, 0x05, 0x08, 0x10, 0x1B, 0x10 };
  sendFrame(ID_BSI_TIME, 7, data);
}

void sendBsiMessage()
{
  // Байт 0: 0x80 — показать, младшие 7 бит — старшая часть кода; 0x7F — скрыть.
  // Байт 1: младшая часть кода. Байт 2: 0x80.
  uint8_t data[8] = { 0x00, 0x00, 0x80, 0x00, 0x00, 0x00, 0x00, 0x00 };
  if (message >= 0)
  {
    data[0] = 0x80 | ((message >> 8) & 0x7F);
    data[1] = message & 0xFF;
  }
  else if (hideFrames > 0)
  {
    hideFrames--;
    data[0] = 0x7F;
    data[1] = lastMessage & 0xFF;
  }
  sendFrame(ID_BSI_MESSAGE, 8, data);
}

void sendPanelButtons()
{
  uint8_t data[6] = { 0, 0, 0, 0, 0, 0 };
  if (buttonFrames > 0 && pressedButton >= 0)
  {
    buttonFrames--;
    data[panelButtons[pressedButton].index] = panelButtons[pressedButton].mask;
  }
  sendFrame(ID_PANEL_BUTTONS, 6, data);
}

void sendRadioFast()
{
  const uint8_t state[4] = { 0xCC, 0x80, 0x40, 0x00 };
  sendFrame(ID_RADIO_STATE, 4, state);
}

void sendRadioSlow()
{
  // Громкость: младшие 5 бит; старшие три бита нулевые, пока громкость меняется, —
  // тогда дисплей показывает всплывающую шкалу
  uint8_t level = volume & 0x1F;
  if (volumePopupFrames > 0) volumePopupFrames--;
  else level |= 0xE0;
  sendFrame(ID_RADIO_VOLUME, 1, &level);

  const uint8_t settings[7] = { 0x3F, 0x48, 0x3F, 0x3F, 0x3F, 0x07, 0x0F };
  sendFrame(ID_RADIO_SETTINGS, 7, settings);
  const uint8_t presence[5] = { 0xF4, 0x05, 0x0C, 0x14, 0x1E };
  sendFrame(ID_RADIO_PRESENCE, 5, presence);
}

void sendRadioInit()
{
  const uint8_t data[8] = { 0x20, 0x01, 0x02, 0x06, 0x12, 0x1F, 0x11, 0x00 };
  sendFrame(ID_RADIO_INIT, 8, data);
}

void hideMessage()
{
  if (message >= 0)
  {
    lastMessage = message;
    message = -1;
    hideFrames = HIDE_FRAMES;
  }
}

void benchWork(unsigned long now)
{
  if (cycleMessages)
  {
    unsigned long phase = now - cycleStarted;
    if (phase >= CYCLE_PERIOD_MS)
    {
      cycleStarted = now;
      message = (lastMessage + 1) & 0xFF;
      Serial.print(F("# message "));
      Serial.println(message);
    }
    else if (phase >= CYCLE_SHOW_MS)
    {
      hideMessage();
    }
  }

  if (now - last100 >= 100)
  {
    last100 = now;
    sendBsiPower();
    sendBsiMessage();
    sendPanelButtons();
    if (radioOn) sendRadioFast();
  }

  if (now - last500 >= 500)
  {
    last500 = now;
    sendBsiStatus();
    sendBsiTime();
    if (radioOn)
    {
      if (!radioInitSent)
      {
        radioInitSent = true;
        sendRadioInit();
      }
      sendRadioSlow();
    }
  }
}

// ---------------------------------------------------------------------------
// Режимы
// ---------------------------------------------------------------------------

// true — на шине есть настоящий BSI: значит, это автомобиль, а не стенд
bool busHasBsi()
{
  struct can_frame frame;
  unsigned long started = millis();
  while (millis() - started < BSI_CHECK_MS)
  {
    if (mcp.readMessage(&frame) == MCP2515::ERROR_OK)
    {
      uint16_t id = frame.can_id & 0x7FF;
      if (id == ID_BSI_POWER || id == ID_BSI_STATUS) return true;
    }
  }
  return false;
}

bool setMode(Mode requested)
{
  // Любое переключение начинается с режима «только слушать»: передача прекращается
  mcp.setListenOnlyMode();
  mode = MODE_IDLE;

  if (requested == MODE_BENCH)
  {
    if (busHasBsi())
    {
      Serial.println(F("ERR BSI_PRESENT"));
      printMode();
      return false;
    }
    mcp.setNormalMode();
    radioInitSent = false;
    hideFrames = 0;
    last100 = last500 = cycleStarted = millis();
  }

  mode = requested;
  lostFrames = 0;
  printMode();
  return true;
}

// ---------------------------------------------------------------------------
// Команды
// ---------------------------------------------------------------------------

bool isWord(const char* token, const char* word)
{
  return strcmp_P(token, word) == 0;
}

void commandTx(char* args)
{
  if (mode != MODE_BENCH)
  {
    Serial.println(F("ERR NOT_BENCH"));
    return;
  }
  char* token = strtok(args, " ");
  if (!token)
  {
    Serial.println(F("ERR ARG"));
    return;
  }
  uint16_t id = strtoul(token, NULL, 16) & 0x7FF;
  uint8_t data[8];
  uint8_t dlc = 0;
  while ((token = strtok(NULL, " ")) != NULL && dlc < 8)
  {
    data[dlc++] = (uint8_t)strtoul(token, NULL, 16);
  }
  sendFrame(id, dlc, data);
  Serial.println(F("OK"));
}

void processLine(char* text)
{
  char* command = strtok(text, " ");
  if (!command) return;
  char* arg = strtok(NULL, "");  // остаток строки

  if (isWord(command, PSTR("?")))
  {
    printHello();
    return;
  }

  if (isWord(command, PSTR("M")))
  {
    if (!arg) Serial.println(F("ERR ARG"));
    else if (isWord(arg, PSTR("IDLE"))) setMode(MODE_IDLE);
    else if (isWord(arg, PSTR("SNIFF"))) setMode(MODE_SNIFF);
    else if (isWord(arg, PSTR("BENCH"))) setMode(MODE_BENCH);
    else Serial.println(F("ERR ARG"));
    return;
  }

  if (isWord(command, PSTR("TX")))
  {
    commandTx(arg);
    return;
  }

  if (!arg)
  {
    Serial.println(F("ERR ARG"));
    return;
  }

  if (isWord(command, PSTR("BTN")))
  {
    for (uint8_t i = 0; i < panelButtonCount; i++)
    {
      if (strcmp(arg, panelButtons[i].name) == 0)
      {
        pressedButton = i;
        buttonFrames = BUTTON_FRAMES;
        Serial.println(F("OK"));
        return;
      }
    }
    Serial.println(F("ERR BUTTON"));
    return;
  }

  if (isWord(command, PSTR("MSG")))
  {
    if (isWord(arg, PSTR("OFF"))) hideMessage();
    else message = constrain(atoi(arg), 0, 0x7FFF);
    cycleMessages = false;
    printState();
    return;
  }

  int value = atoi(arg);
  if (isWord(command, PSTR("IGN"))) ignition = value != 0;
  else if (isWord(command, PSTR("REV"))) reverseGear = value != 0;
  else if (isWord(command, PSTR("DARK"))) darkMode = value != 0;
  else if (isWord(command, PSTR("TEMP"))) outsideTemp = constrain(value, -40, 87);
  else if (isWord(command, PSTR("COOL"))) coolantTemp = constrain(value, -40, 215);
  else if (isWord(command, PSTR("BRIGHT"))) brightness = constrain(value, 0, 15);
  else if (isWord(command, PSTR("RADIO")))
  {
    radioOn = value != 0;
    radioInitSent = false;
  }
  else if (isWord(command, PSTR("VOL")))
  {
    volume = constrain(value, 0, 30);
    volumePopupFrames = VOLUME_POPUP_FRAMES;
  }
  else if (isWord(command, PSTR("CYCLE")))
  {
    cycleMessages = value != 0;
    cycleStarted = millis() - CYCLE_PERIOD_MS;  // первое сообщение — сразу
    if (!cycleMessages) hideMessage();
  }
  else
  {
    Serial.println(F("ERR COMMAND"));
    return;
  }
  printState();
}

void readCommands()
{
  while (Serial.available())
  {
    char c = Serial.read();
    if (c == '\r' || c == '\n')
    {
      if (lineLen > 0)
      {
        line[lineLen] = 0;
        lineLen = 0;
        processLine(line);
      }
    }
    else if (lineLen < sizeof(line) - 1)
    {
      line[lineLen++] = c;
    }
  }
}

// ---------------------------------------------------------------------------

void setup()
{
  Serial.begin(SERIAL_BAUD);
  SPI.begin();
  mcp.reset();
  if (mcp.setBitrate(CAN_SPEED, CAN_CLOCK) != MCP2515::ERROR_OK)
  {
    Serial.println(F("ERR CAN_INIT"));
  }
  mcp.setListenOnlyMode();
  printHello();
}

void loop()
{
  unsigned long now = millis();
  readCommands();

  if (mode == MODE_IDLE) return;

  // Кадры шины: в режиме SNIFF — все, на стенде — то, что шлёт дисплей
  struct can_frame frame;
  uint8_t budget = 8;
  while (budget-- > 0 && mcp.readMessage(&frame) == MCP2515::ERROR_OK)
  {
    printFrame(frame);
  }

  // Переполнение буферов приёма: кадры приходят быстрее, чем уходят в порт
  if (mcp.getErrorFlags() & (MCP2515::EFLG_RX0OVR | MCP2515::EFLG_RX1OVR))
  {
    mcp.clearRXnOVR();
    lostFrames++;
  }
  if (lostFrames > 0 && now - lastLostReport >= 1000)
  {
    lastLostReport = now;
    Serial.print(F("LOST "));
    Serial.println(lostFrames);
    lostFrames = 0;
  }

  if (mode == MODE_BENCH) benchWork(now);
}
