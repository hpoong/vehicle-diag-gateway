"""로컬 프로세스 제어: PostgreSQL(Compose), gateway 2개 인스턴스, receiver(chaos 프로필).

실제 프로세스를 강제 종료(SIGKILL)하거나 멈추는(SIGSTOP) 장애를 만들기 위해
bootJar를 직접 실행하고 PID를 build/run/에 기록한다.

    python3 -m scripts.services build                 # bootJar 빌드
    python3 -m scripts.services start receiver gateway
    python3 -m scripts.services kill gateway           # SIGKILL (S7)
    python3 -m scripts.services pause gateway          # SIGSTOP (S9, macOS·Linux)
    python3 -m scripts.services status

JDK는 JAVA_HOME, 없으면 PATH의 java를 사용한다.
gateway 전송 설정은 시나리오를 빨리 보기 위해 기본보다 짧게 둔다 (SIM_GATEWAY_ARGS로 변경).
"""
import argparse
import json
import os
import shlex
import signal
import socket
import subprocess
import sys
import time

from scripts.common import ROOT, RUN_DIR, healthy

DEMO_GATEWAY_ARGS = shlex.split(os.environ.get(
    "SIM_GATEWAY_ARGS",
    "--gateway.delivery.backoff.base=1s --gateway.delivery.backoff.max=4s "
    "--gateway.delivery.max-attempts=6 --gateway.delivery.lease=10s"))

SERVICES = {
    "gateway": {"module": "gateway", "port": 8080, "env": {"GATEWAY_PORT": "8080", "GATEWAY_INSTANCE_ID": "gw-1"},
                "args": DEMO_GATEWAY_ARGS},
    "gateway2": {"module": "gateway", "port": 8082, "env": {"GATEWAY_PORT": "8082", "GATEWAY_INSTANCE_ID": "gw-2"},
                 "args": DEMO_GATEWAY_ARGS},
    "receiver": {"module": "receiver", "port": 8081, "env": {"RECEIVER_PORT": "8081"},
                 "args": ["--spring.profiles.active=chaos"]},
}
POSTGRES_PORT = int(os.environ.get("POSTGRES_PORT", "5432"))
CAN_PAUSE = hasattr(signal, "SIGSTOP")


def _pid_file(name):
    return RUN_DIR / (name + ".pid")


def _meta_file(name):
    return RUN_DIR / (name + ".json")


def log_file(name):
    return RUN_DIR / (name + ".log")


def _java():
    home = os.environ.get("JAVA_HOME")
    if home:
        return os.path.join(home, "bin", "java")
    return "java"


def _jar(module):
    libs = ROOT / module / "build" / "libs"
    jars = sorted(p for p in libs.glob("*.jar") if not p.name.endswith("-plain.jar")) if libs.exists() else []
    if not jars:
        raise RuntimeError("{} jar가 없습니다. 먼저 build를 실행하세요 (python3 -m scripts.services build)".format(module))
    return jars[-1]


def _read_pid(name):
    try:
        return int(_pid_file(name).read_text().strip())
    except (OSError, ValueError):
        return None


def _alive(pid):
    if pid is None:
        return False
    try:
        os.kill(pid, 0)
    except OSError:
        return False
    try:  # 좀비(종료됐지만 회수 전) 제외
        stat = subprocess.run(["ps", "-o", "stat=", "-p", str(pid)], capture_output=True, text=True).stdout.strip()
        return bool(stat) and not stat.startswith("Z")
    except OSError:
        return True


def _paused(pid):
    if not CAN_PAUSE or pid is None:
        return False
    try:
        stat = subprocess.run(["ps", "-o", "stat=", "-p", str(pid)], capture_output=True, text=True).stdout.strip()
    except OSError:
        return False
    return stat.startswith("T")


def _port_open(port, host="127.0.0.1"):
    with socket.socket() as s:
        s.settimeout(0.5)
        return s.connect_ex((host, port)) == 0


def status():
    out = {"postgres": {"state": "up" if _port_open(POSTGRES_PORT) else "down", "port": POSTGRES_PORT}}
    for name, svc in SERVICES.items():
        pid = _read_pid(name)
        alive = _alive(pid)
        url = "http://localhost:{}".format(svc["port"])
        if alive and _paused(pid):
            state = "paused"
        elif alive:
            state = "up" if healthy(url) else "starting"
        elif healthy(url):
            state = "external"   # bootRun 등 이 스크립트 밖에서 실행 중
        else:
            state = "down"
        meta = {}
        try:
            meta = json.loads(_meta_file(name).read_text()) if alive else {}
        except (OSError, ValueError):
            pass
        out[name] = {"state": state, "pid": pid if alive else None, "port": svc["port"], "url": url,
                     "options": meta.get("options", {})}
    return out


def start(name, wait=True, timeout=90, naive=False, extra_args=None):
    svc = SERVICES[name]
    if _alive(_read_pid(name)):
        return "already running"
    if _port_open(svc["port"]):
        raise RuntimeError("{} 포트 {}가 이미 사용 중입니다 (bootRun 등으로 실행 중인지 확인)".format(name, svc["port"]))
    RUN_DIR.mkdir(parents=True, exist_ok=True)
    args = list(svc["args"]) + list(extra_args or [])
    if name == "receiver" and naive:
        args.append("--receiver.compare.naive-insert=true")
    cmd = [_java(), "-jar", str(_jar(svc["module"]))] + args
    env = dict(os.environ)
    env.update(svc["env"])
    log = open(log_file(name), "ab")
    log.write("\n=== {} start: {}\n".format(time.strftime("%Y-%m-%d %H:%M:%S"), " ".join(cmd)).encode())
    log.flush()
    proc = subprocess.Popen(cmd, cwd=str(ROOT), env=env, stdout=log, stderr=subprocess.STDOUT,
                            stdin=subprocess.DEVNULL, start_new_session=True)
    _pid_file(name).write_text(str(proc.pid))
    _meta_file(name).write_text(json.dumps({"options": {"naive": naive} if name == "receiver" else {}}))
    if wait:
        wait_healthy(name, timeout)
    return "started pid={}".format(proc.pid)


def wait_healthy(name, timeout=90):
    svc = SERVICES[name]
    url = "http://localhost:{}".format(svc["port"])
    deadline = time.time() + timeout
    while time.time() < deadline:
        if healthy(url):
            return True
        if not _alive(_read_pid(name)):
            raise RuntimeError("{} 프로세스가 종료됐습니다. 로그: {}".format(name, log_file(name)))
        time.sleep(0.5)
    raise RuntimeError("{} health 대기 시간 초과 ({}s)".format(name, timeout))


def _signal(name, sig):
    pid = _read_pid(name)
    if not _alive(pid):
        return "not running"
    os.kill(pid, sig)
    return "sent {} to pid={}".format(getattr(sig, "name", sig), pid)


def stop(name, timeout=20):
    pid = _read_pid(name)
    if not _alive(pid):
        return "not running"
    if _paused(pid):
        os.kill(pid, signal.SIGCONT)
    os.kill(pid, signal.SIGTERM)
    deadline = time.time() + timeout
    while time.time() < deadline and _alive(pid):
        time.sleep(0.3)
    if _alive(pid):
        os.kill(pid, signal.SIGKILL)
        return "stopped (SIGKILL after timeout)"
    return "stopped"


def kill(name):
    """처리 중 강제 종료. 그레이스풀 종료 없이 선점(lease)이 남는다."""
    return _signal(name, signal.SIGKILL)


def pause(name):
    if not CAN_PAUSE:
        raise RuntimeError("이 OS는 SIGSTOP을 지원하지 않습니다")
    return _signal(name, signal.SIGSTOP)


def resume(name):
    if not CAN_PAUSE:
        raise RuntimeError("이 OS는 SIGCONT를 지원하지 않습니다")
    return _signal(name, signal.SIGCONT)


def postgres(action):
    cmd = ["docker", "compose", "up", "-d", "--wait", "postgres"] if action == "up" else \
        ["docker", "compose", "stop", "postgres"]
    res = subprocess.run(cmd, cwd=str(ROOT), capture_output=True, text=True)
    if res.returncode != 0:
        raise RuntimeError((res.stderr or res.stdout).strip()[-800:])
    return "postgres " + action


def build(on_line=None):
    gradlew = "gradlew.bat" if os.name == "nt" else "./gradlew"
    cmd = [gradlew, ":gateway:bootJar", ":receiver:bootJar", "--console=plain"]
    proc = subprocess.Popen(cmd, cwd=str(ROOT), stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    for line in proc.stdout:
        if on_line:
            on_line(line.rstrip())
    if proc.wait() != 0:
        raise RuntimeError("bootJar 빌드 실패 (exit {})".format(proc.returncode))
    return "built"


def tail(name, lines=80):
    try:
        with open(log_file(name), "rb") as f:
            f.seek(0, os.SEEK_END)
            size = f.tell()
            f.seek(max(0, size - 64 * 1024))
            text = f.read().decode("utf-8", "replace")
    except OSError:
        return ""
    return "\n".join(text.splitlines()[-lines:])


ACTIONS = {"start": start, "stop": stop, "kill": kill, "pause": pause, "resume": resume}


def main(argv=None):
    p = argparse.ArgumentParser(prog="python3 -m scripts.services", description="로컬 서비스 제어")
    p.add_argument("action", choices=["build", "status", "up", "down", "logs"] + sorted(ACTIONS))
    p.add_argument("names", nargs="*", help="gateway | gateway2 | receiver (기본: 전체)")
    p.add_argument("--naive", action="store_true", help="receiver를 중복 제거 없는 비교 모드로 시작 (S12)")
    args = p.parse_args(argv)
    if args.action == "build":
        print(build(on_line=print))
    elif args.action == "status":
        print(json.dumps(status(), indent=2))
    elif args.action in ("up", "down"):
        print(postgres(args.action))
    elif args.action == "logs":
        for name in args.names or ["gateway"]:
            print(tail(name))
    else:
        names = args.names or (["receiver", "gateway"] if args.action == "start" else list(SERVICES))
        for name in names:
            kwargs = {"naive": args.naive} if args.action == "start" else {}
            print("{}: {}".format(name, ACTIONS[args.action](name, **kwargs)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
