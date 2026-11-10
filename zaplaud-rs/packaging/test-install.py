#!/usr/bin/env python3
"""Exercita o instalador sem tocar na sessão ou nos dados reais do usuário."""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


packaging = Path(__file__).resolve().parent
installer = packaging.parent / "scripts/install-linux.sh"
sizes = (16, 24, 32, 48, 64, 128, 256, 512, 1024)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def exercise(root, custom_xdg):
    home = root / "home com espaços"
    home.mkdir(parents=True)
    data = home / ("dados personalizados" if custom_xdg else ".local/share")
    environment = dict(os.environ, HOME=str(home))
    environment.pop("XDG_DATA_HOME", None)
    if custom_xdg:
        environment["XDG_DATA_HOME"] = str(data)
    bundle = root / "bundle"
    bundle.mkdir()
    shutil.copy2(installer, bundle / "install-linux.sh")
    shutil.copytree(packaging / "icons", bundle / "packaging/icons")
    shutil.copy2(packaging / "zaplaud.desktop", bundle / "packaging/zaplaud.desktop")
    # sleep permite observar o inode em execução sem depender de uma sessão gráfica.
    shutil.copy2(shutil.which("sleep"), bundle / "zaplaud")
    command = ["bash", str(bundle / "install-linux.sh")]

    def run(*arguments, success=True):
        result = subprocess.run(command + list(arguments), env=environment, capture_output=True, text=True)
        assert (result.returncode == 0) == success, result.stdout + result.stderr
        return result

    subprocess.run(
        ["bash", str(installer), str(bundle / "zaplaud")],
        env=environment, check=True, capture_output=True, text=True,
    )
    binary = home / ".local/bin/zaplaud"
    desktop = data / "applications/zaplaud.desktop"
    first = (digest(binary), digest(desktop))
    run()
    assert first == (digest(binary), digest(desktop))
    for size in sizes:
        icon = data / f"icons/hicolor/{size}x{size}/apps/zaplaud.png"
        assert icon.is_file(), icon
    validator = shutil.which("desktop-file-validate")
    if validator:
        subprocess.run([validator, str(desktop)], check=True)
    assert "StartupWMClass=zaplaud\n" in desktop.read_text()
    assert f'Exec="{binary}"\n' in desktop.read_text()
    process = subprocess.Popen([str(binary), "60"])
    try:
        assert "Feche o Zaplaud" in run(success=False).stderr
        assert "Feche o Zaplaud" in run("--uninstall", success=False).stderr
        assert first == (digest(binary), digest(desktop))
    finally:
        process.terminate()
        process.wait()
    marker = data / "Zaplaud/preservar"
    marker.parent.mkdir()
    marker.write_text("dados do usuário")
    unrelated = data / "applications/outro.desktop"
    unrelated.write_text("outro aplicativo")
    run("--uninstall")
    run("--uninstall")
    assert not binary.exists() and not desktop.exists()
    assert marker.read_text() == "dados do usuário"
    assert unrelated.read_text() == "outro aplicativo"
    assert not list((data / "icons/hicolor").glob("*/apps/zaplaud.png"))


with tempfile.TemporaryDirectory(prefix="zaplaud-install-") as temporary:
    for custom_xdg in (False, True):
        exercise(Path(temporary) / str(custom_xdg), custom_xdg)
print("OK: HOME temporário, XDG, espaços, reinstalação, bloqueio em execução e desinstalação idempotente.")
print("desktop-file-validate: " + ("OK" if shutil.which("desktop-file-validate") else "indisponível"))
