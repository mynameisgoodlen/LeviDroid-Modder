import subprocess
import sys


def fail(message):
    raise SystemExit(message)


def main():
    if len(sys.argv) != 3:
        fail("usage: generate_maesdk_stub.py <libmaesdk.so> <out.S>")
    source, out_path = sys.argv[1], sys.argv[2]
    dump = subprocess.run(
        ["readelf", "--dyn-syms", "-W", source],
        capture_output=True,
        text=True,
    )
    if dump.returncode != 0:
        fail(dump.stderr.strip() or "readelf failed")
    funcs, objects = [], []
    seen = set()
    for line in dump.stdout.splitlines():
        if ":" not in line or not line.strip().startswith(tuple("0123456789")):
            continue
        fields = line.split()
        if len(fields) < 8:
            continue
        name_index = len(fields) - 1
        name = fields[name_index].split("@")[0]
        kind = fields[name_index - 4]
        size_text = fields[name_index - 5]
        section = fields[name_index - 2]
        if section == "UND":
            continue
        if name in seen:
            continue
        seen.add(name)
        if name.startswith("Java_"):
            continue
        if not name.replace("_", "").replace("$", "").isalnum():
            continue
        try:
            size = int(size_text, 0)
        except ValueError:
            continue
        if kind == "FUNC":
            funcs.append((name, size))
        elif kind == "OBJECT":
            objects.append((name, size))
    if not funcs and not objects:
        fail("no defined symbols found")
    text = [".arm", ".section .text", "__maesdk_stub_noop:", "    bx lr"]
    vtables = ['.section .data.rel.ro,"aw"']
    rodata = [".section .rodata"]
    for name, size in funcs:
        text.append(f".global {name}")
        text.append(f".type {name}, %function")
        text.append(f"{name}:")
        text.append("    mov r0, #0")
        text.append("    bx lr")
        text.append(f".size {name}, .-{name}")
    for name, size in objects:
        if name.startswith(("_ZTV", "_ZTT", "_ZTC")):
            vtables.append(f".global {name}")
            vtables.append(f".type {name}, %object")
            vtables.append(".balign 4")
            vtables.append(f"{name}:")
            words = size // 4
            vtables.append(f"    .rept {words}")
            vtables.append("    .word __maesdk_stub_noop")
            vtables.append("    .endr")
            vtables.append(f"    .zero {size % 4}")
            vtables.append(f".size {name}, {size}")
        else:
            rodata.append(f".global {name}")
            rodata.append(f".type {name}, %object")
            rodata.append(".balign 4")
            rodata.append(f"{name}:")
            rodata.append(f"    .zero {size}")
            rodata.append(f".size {name}, {size}")
    payload = text + vtables + rodata + ['.section .note.GNU-stack,"",%progbits']
    with open(out_path, "w", encoding="utf-8") as out:
        out.write("\n".join(payload) + "\n")
    print(f"{len(funcs)} functions, {len(objects)} objects -> {out_path}")


if __name__ == "__main__":
    main()
