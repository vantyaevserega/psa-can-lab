"""Генератор схемы подключения: python gen_schematic.py — пишет schematic.svg.

Стиль «метки цепей»: блок — модуль, у каждого вывода подписана цепь; одинаковые метки соединяются.
Железо простое (навесной монтаж): Arduino Nano + модуль MCP2515, питание от USB.
"""
import html
import pathlib

COLORS = {
    "pwr": "#c62828",
    "gnd": "#263238",
    "sig": "#1565c0",
    "can": "#2e7d32",
    "out": "#e65100",
}
ROW = 26
STUB = 34


class Svg:
    def __init__(self, width, height, title):
        self.parts = [
            f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {width} {height}" '
            f'width="{width}" height="{height}" font-family="Segoe UI, Arial, sans-serif">',
            f'<rect width="{width}" height="{height}" fill="#ffffff"/>',
        ]
        self.text(24, 34, title, 20, "#111", weight="600")

    def text(self, x, y, value, size=13, fill="#222", anchor="start", weight="400"):
        self.parts.append(
            f'<text x="{x}" y="{y}" font-size="{size}" fill="{fill}" text-anchor="{anchor}" '
            f'font-weight="{weight}">{html.escape(value)}</text>'
        )

    def block(self, x, y, width, title, left=(), right=(), notes=()):
        rows = max(len(left), len(right))
        height = 34 + rows * ROW + (8 + 17 * len(notes) if notes else 0) + 8
        self.parts.append(
            f'<rect x="{x}" y="{y}" width="{width}" height="{height}" rx="6" fill="#f5f7fa" '
            f'stroke="#455a64" stroke-width="1.5"/>'
        )
        self.parts.append(f'<path d="M{x} {y + 28}H{x + width}" stroke="#455a64" stroke-width="1"/>')
        self.text(x + width / 2, y + 19, title, 13.5, "#111", "middle", "600")

        for is_left, pins in ((True, left), (False, right)):
            for i, (name, net, kind) in enumerate(pins):
                py = y + 34 + i * ROW + ROW / 2
                color = COLORS[kind]
                edge = x if is_left else x + width
                end = edge - STUB if is_left else edge + STUB
                self.parts.append(f'<path d="M{edge} {py}H{end}" stroke="{color}" stroke-width="2.2"/>')
                self.parts.append(f'<circle cx="{end}" cy="{py}" r="3.2" fill="{color}"/>')
                if is_left:
                    self.text(x + 7, py + 4.5, name, 12.5)
                    self.text(end - 7, py + 4.5, net, 12.5, color, "end", "600")
                else:
                    self.text(x + width - 7, py + 4.5, name, 12.5, anchor="end")
                    self.text(end + 7, py + 4.5, net, 12.5, color, "start", "600")

        for i, note in enumerate(notes):
            self.text(x + 8, y + 34 + rows * ROW + 20 + i * 17, note, 11.5, "#546e7a")

    def legend(self, x, y, lines):
        for i, line in enumerate(lines):
            self.text(x, y + i * 18, line, 12.5, "#37474f")

    def save(self, path):
        path.write_text("\n".join(self.parts + ["</svg>"]) + "\n", encoding="utf-8", newline="\n")


def schematic(path):
    g = Svg(1120, 330, "PSA CAN Lab — схема")

    g.block(140, 80, 180, "Шина",
            right=[("CAN-H", "CAN_H", "can"), ("CAN-L", "CAN_L", "can")])
    g.block(500, 80, 210, "Модуль MCP2515 (8 МГц)",
            left=[("VCC", "+5V", "pwr"), ("GND", "GND", "gnd"), ("CS", "CAN_CS", "sig"),
                  ("SI", "MOSI", "sig"), ("SO", "MISO", "sig"), ("SCK", "SCK", "sig"),
                  ("H", "CAN_H", "can"), ("L", "CAN_L", "can")])
    g.block(890, 80, 190, "Arduino Nano",
            left=[("5V", "+5V", "pwr"), ("GND", "GND", "gnd"), ("D10", "CAN_CS", "sig"),
                  ("D11", "MOSI", "sig"), ("D12", "MISO", "sig"), ("D13", "SCK", "sig")],
            right=[("USB", "USB", "out")])
    g.save(path)


if __name__ == "__main__":
    here = pathlib.Path(__file__).parent
    schematic(here / "schematic.svg")
