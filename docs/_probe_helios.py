import pathlib
import paramiko

props = {}
text = pathlib.Path(r"D:\_projs\Новая папка\src\test\resources\helios.local.properties").read_text(encoding="utf-8")
for line in text.splitlines():
    if not line.strip() or line.startswith("#") or "=" not in line:
        continue
    key, value = line.split("=", 1)
    props[key.strip()] = value.strip()

client = paramiko.SSHClient()
client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
client.connect(
    props["host"],
    port=int(props["port"]),
    username=props["user"],
    password=props["password"],
    look_for_keys=False,
    allow_agent=False,
    timeout=30,
    banner_timeout=30,
    auth_timeout=30,
)
command = r"""
echo '=== java ==='
java -version 2>&1 || true
echo '=== javac ==='
javac -version 2>&1 || true
echo '=== sql ==='
ls "$HOME/skhodim-etap2/sql" 2>/dev/null || echo 'no remote sql dir'
echo '=== users ==='
/usr/local/bin/psql -h pg -d studs -At -c 'SELECT count(*) FROM s467667.app_user;'
"""
stdin, stdout, stderr = client.exec_command(command, timeout=40)
print(stdout.read().decode("utf-8", "replace"))
print(stderr.read().decode("utf-8", "replace"))
client.close()
