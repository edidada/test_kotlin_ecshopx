import hashlib
import hmac
import json
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

"""M2-1（开放平台网关 + Webhook 出站）实跑冒烟：真 fat jar + 常驻 H2 库上走一遍 docs/04 §25.6 的四道门槛。

M21EndToEndTest 管"闸门判据对不对"，本脚本管另外三件内存测试给不了的事：

 1. **签名必须能被另一个语言独立算出来**（门槛①的跨语言那一半）。
    下面 `assemble/gen_sign` 是照 PHP `OpenapiCheck::assemble` 的语义**重写**的一遍（不是翻译 Kotlin 代码）：
    字节序排键、`null` 整项跳过、布尔转 `"1"/"0"`、列表下标按字符串序（`"0" < "1" < "10" < "2"`）。
    两边对上才算这条判据成立；对不上就是对接方那句"签名错误"的真实成因。
 2. **出站链在常驻库上真的能打通**（门槛③）。脚本自己起一个本机假接收服务器（`SimWebhookReceiver` 的 python 对应物），
    两层签名都验：`X-Ecx-Signature` 覆盖的是**收到的原始字节**，体内 `sign` 按"去掉 sign 后重组"比对。
    先让它回 500 再回 200，断 `attempts` 从 1 走到 2、状态从 `pending` 走到 `success`、`msg_id` 不变。
 3. **重放必须在委托之前挡住**（门槛①后半）。同一个 `nonce` 连打两次写面
    （`ecx.member_point.update`），第二次 `E4002` 且**余额二次读回完全相同** ——
    这不是"业务侧幂等兜住了"，那一层挡不住"同一句话执行两遍"。

本脚本没有 SQL 通道：密钥只从 seed（演示假密钥，仅本地自签自验）与 OP-A2/A4 的响应里取，
订阅只通过 WH-A2 建，事件只通过正常下单支付触发，投递结果只通过 WH-A5/A6 读。

覆盖 8 段：签名单正例 → 十一道闸门位次与边界 → 分发与租户边界 → 后台六条档案面 →
订阅面四道 422 → 真实事件出站（order.created / order.paid）→ 失败退避与手动重推 → 权限面与收尾清理。

跑法：先按 docs/05 的方式起服务（18080，`ECX_WEBHOOK_ENABLED` 默认为真），再 `python tools/m21_openapi_smoke.py`。
"""

BASE = "http://127.0.0.1:18080"
PW = "admin" + "123"
SPW = "shop" + "123"
VCODE = "8888"
MEMBER = "13800000001"
ITEM = 3                                  # seed 价 8900 分/件
RUN = "M21%d" % int(time.time())

# seed 的两把演示密钥（§25.5：假密钥，只用于本地自签自验，不是任何真实凭据）
AK1 = "ak_1a2b3c4d5e6f7081"
SK1 = "sk_1a2b3c4d5e6f708192a3b4c5d6e7f809"
OUT_AK1 = "ek_demo_out_0001"
OUT_SK1 = "esk_2b3c4d5e6f708192a3b4c5d6e7f809"
AK2 = "ak_2b3c4d5e6f708192"               # company_id=2，库里没有它的业务数据
SK2 = "sk_2b3c4d5e6f708192a3b4c5d6e7f8091a"

RECV_PORT = 18099
RECV_URL = "http://127.0.0.1:%d/%s" % (RECV_PORT, RUN)
received = []                             # [(headers_dict, raw_body)]
mode = {"status": 200, "reset_next": False}
_seq = [0]

ok = []


# ===================== 签名（照 PHP assemble 语义重写，不复用 Kotlin 实现） =====================

def assemble(params):
    out = []
    # 字节序 = 对 utf-8 bytes 的默认排序，等价 PHP `ksort($p, SORT_STRING)`
    for k in sorted(params.keys(), key=lambda s: s.encode("utf-8")):
        v = params[k]
        if v is None:
            continue                      # 整项跳过：键也不出现
        out.append(k + leaf(v))
    return "".join(out)


def leaf(v):
    if isinstance(v, bool):
        return "1" if v else "0"          # 先判 bool：Python 里 True 也是 int
    if isinstance(v, str):
        return v
    if isinstance(v, float):
        return str(int(v)) if v == int(v) else repr(v)   # PHP `(string)1.0 == "1"`
    if isinstance(v, int):
        return str(v)
    if isinstance(v, dict):
        return assemble(v)
    if isinstance(v, list):
        return assemble({str(i): e for i, e in enumerate(v)})   # 下标按字符串序参与排序
    return str(v)


def gen_sign(params, secret):
    body = {k: v for k, v in params.items() if k != "sign"}
    return hashlib.md5((secret + assemble(body) + secret).encode("utf-8")).hexdigest().upper()


def hmac_hex(secret, ts, raw_body):
    return hmac.new(secret.encode(), (ts + "\n" + raw_body).encode(), hashlib.sha256).hexdigest()


# ===================== 假接收服务器（SimWebhookReceiver 的 python 对应物） =====================

class Receiver(BaseHTTPRequestHandler):
    def do_POST(self):
        n = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(n).decode("utf-8")
        # `path` 一起记下：常驻库上上一轮留下的订阅会打到**同一个 host:port**（只有 path 里那次 RUN 不同），
        # 不记这一位就只能"多收几条"当噪音忍着，精确条数断言随之作废（下面的 envelopes/raw_hits 按它筛）
        hit = ({**{k.lower(): v for k, v in self.headers.items()}, "path": self.path}, raw)
        # reset_next：一个字节应答都不给就断开 —— Java 侧必须把这看成"可重试失败"（状态位 0）
        if mode["reset_next"]:
            mode["reset_next"] = False
            received.append(hit)
            self.close_connection = True
            return
        received.append(hit)
        payload = b'{"ok":true}' if mode["status"] == 200 else b'{"err":"busy"}'
        self.send_response(mode["status"])
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, *a):            # 静默：默认那行 access log 会盖住脚本的断言输出
        pass


def wait_events(topic, timeout=15):
    """等到至少一条某事件的消息，返回收到的全部该事件信封（轮询而不是睡死：CI 上唤醒时间不稳）"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        hits = envelopes(topic)
        if hits:
            return hits
        time.sleep(0.3)
    return envelopes(topic)


def own_hits():
    """只算**打到本轮那个 path** 的收包：上一轮残留订阅推过来的那几条不属于这一轮，不能参与计数"""
    return [(h, r) for h, r in list(received) if h.get("path") == "/" + RUN]


def envelopes(topic=None):
    out = []
    for h, raw in own_hits():
        try:
            e = json.loads(raw)
        except ValueError:
            continue
        if topic is None or e.get("topic") == topic:
            out.append((h, e))
    return out


def raw_hits(event):
    """按 `X-Ecx-Event` 头取**原始字节**那一份：验 HMAC 必须用收到的 body，解析再序列化就不算了"""
    return [(h, r) for h, r in own_hits() if h.get("x-ecx-event") == event]


def wait_delivery(delivery_id, status, timeout=120):
    """等**真实的那支 30 秒定时作业**把一行推到指定状态；脚本侧只读 WH-A6，不碰投递代码。

    e2e 把作业推到了 1 小时（`deliverDue` 由测试直接调），所以"到期之后没人催也自己会动"
    这一位只有在常驻服务上才断得下来 —— 而它恰恰是对接方最关心的一位：
    对端宕机半小时后恢复，事件不需要任何人去点按钮就该继续走。
    """
    deadline = time.time() + timeout
    while True:
        row = data("GET", "/api/webhook/delivery/%d" % delivery_id, token=atok)
        if row["status"] == status or time.time() >= deadline:
            return row
        time.sleep(2)


# ===================== HTTP 帮助 =====================

def check(name, cond, extra=""):
    ok.append(bool(cond))
    print(("PASS " if cond else "FAIL ") + name + ("" if cond else " -> " + str(extra)[:400]))


def req(method, path, body=None, token=None, ctype="application/json", raw_body=None):
    payload = raw_body if raw_body is not None else (json.dumps(body).encode() if body is not None else None)
    r = urllib.request.Request(BASE + path, data=payload, method=method)
    if payload is not None:
        r.add_header("Content-Type", ctype)
    if token:
        r.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(r, timeout=30) as resp:
            txt = resp.read().decode("utf-8")
            return resp.status, (json.loads(txt) if txt.startswith(("{", "[")) else txt)
    except urllib.error.HTTPError as e:
        txt = e.read().decode("utf-8")
        try:
            return e.code, json.loads(txt)
        except ValueError:
            return e.code, txt


def data(method, path, body=None, token=None):
    st, res = req(method, path, body, token)
    assert st == 200, "%s %s -> %s %s" % (method, path, st, res)
    return res["data"]


def login(user, pw=PW, path="/api/operator/login"):
    if path == "/api/operator/login":
        return data("POST", path, {"username": user, "password": pw})["token"]
    return data("POST", path, {"auth_type": "local", "username": user,
                               "check_type": "mobile", "vcode": pw})["token"]


def nonce():
    _seq[0] += 1
    return "%s-n%d" % (RUN, _seq[0])


def call(app_key, secret, method_name, extra=None, verb="GET", version="2.0",
         ts_offset=0, nonce_value=None, drop=(), sign_override=None, bad_byte=None,
         body=None, path_style=False):
    """打一次网关。签名覆盖的是 query ∪ body 全集（`sign` 自身除外），与 Kotlin 侧 `OpenapiParams.merge` 同一判据。

    - 默认 `method` 走 query（OA-G1）；`path_style=True` 时走 `/api/openapi/{method}`（OA-G2），
      此时 `method` 不在参数表里，签名也就不含它 —— 两种写法各自自洽，都要能通；
    - `body` 用真实 JSON 结构（列表/对象/布尔/null），query 只能送字符串，
      所以"嵌套怎么折"这条判据必须从 body 那一路测。
    """
    q = {"app_key": app_key, "timestamp": str(int(time.time()) + ts_offset),
         "version": version, "nonce": nonce_value or nonce()}
    if not path_style:
        q["method"] = method_name
    if extra:
        q.update({k: str(v) for k, v in extra.items()})
    for k in drop:
        q.pop(k, None)
    merged = dict(q)
    if body:
        merged.update(body)                   # 同键冲突时请求体胜出（与 Laravel `Request::all()` 同序）
    if "sign" not in drop:
        # 签完再放进 query：`sign` 自己永远不参与签名（gen_sign 里已排除）
        q["sign"] = sign_override if sign_override is not None else gen_sign(merged, secret)
    # **篡改发生在签好之后**，这一行顺序就是"改一个字节"这条判据的全部意义。
    # 先改再签，签就跟着一起变了，请求是自洽的、服务端只能回 E0000 ——
    # 脚本第一次实跑就红在这里（`bad_byte` 原先写在 `merged` 之前，等于没改）
    if bad_byte:
        if bad_byte in q:
            q[bad_byte] = q[bad_byte] + "x"
        elif body is not None and bad_byte in body:
            body = dict(body)
            body[bad_byte] = str(body[bad_byte]) + "x"
    qs = urllib.parse.urlencode(q)
    path = ("/api/openapi/" + method_name if path_style else "/api/openapi") + "?" + qs
    if verb == "POST":
        return req("POST", path, body=body if body is not None else {})
    return req("GET", path)


# ===================== 起接收服务器 =====================

srv = ThreadingHTTPServer(("127.0.0.1", RECV_PORT), Receiver)
threading.Thread(target=srv.serve_forever, daemon=True).start()


def code_of(res):
    return res.get("code") if isinstance(res, dict) else None


atok = login("admin", PW)

# 常驻库会把上一轮的订阅留下来（`webhook_subscription` 是配置，不是这一轮的产物）。
# 上一轮那次中途中断的运行留下了两条 order.* 订阅，URL 与本脚本**同一个 host:port**（只有 path 不同），
# 于是这一轮的假接收服务器同时收得到"上一轮订阅"的推送 —— `len(...) == 1` 那种精确断言当场变成 2。
# 这不是测试写歪：它照出的是"脚本在常驻库上不幂等"。开场先 sweep 掉本机 18099 的残留订阅（投递记录留着，
# 它们是证据），再加一层 path 过滤（见 `envelopes/raw_hits`），两道都上才敢断精确条数。
stale = [r for r in data("GET", "/api/webhook/subscriptions?developer_id=1&pageSize=100",
                         token=atok)["list"] if ":18099/" in r["url"]]
for r in stale:
    req("DELETE", "/api/webhook/subscription/%d" % r["id"], token=atok)
print("[M2-1 冒烟 %s] 开场清掉上一轮残留的本机订阅 %d 条" % (RUN, len(stale)))

print("\n== 1. 单正例：签对的请求出数据 ==")
st, res = call(AK1, SK1, "ecx.items.entity.get", {"pageSize": "1"})
check("OA-G1 单正例通（HTTP 200 + E0000）", st == 200 and code_of(res) == "E0000", res)
check("应答包裹开时信封恰是四键（§25.1 末段）", isinstance(res, dict) and
      set(res) == {"status", "code", "message", "data"}, sorted(res or []))
check("OA-M7 商品列表出 total_count/list", res["data"]["total_count"] >= 1 and isinstance(res["data"]["list"], list), res["data"].get("total_count"))

# 11 元素列表 + 嵌套对象 + 布尔 + null：下标字符串序（0<1<10<2）只有跨语言重算才测得出来。
# 用 OA-M4（POST）当载体：它注册的是 POST，且单号不存在会在委托里抛 NOT_FOUND → E5301。
# **拿到 E5301 就意味着第 6 道闸门已放行**（签错的分支在它前面），所以这一条同时证明了两件事。
st, res = call(AK1, SK1, "ecx.order.deliver", verb="POST", body={
    "order_id": 999999999999999,
    "probe_list": ["a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k"],
    "probe_obj": {"z": 1, "a": {"y": [1, 2, 10, 20, 3]}, "b": True, "n": None},
})
check("11 元素列表按下标字符串序折叠也能对上签（错签会先撞 E4004）", code_of(res) == "E5301", res)

st, res = call(AK1, SK1, "ecx.items.entity.get", {"pageSize": "1"}, path_style=True)
check("OA-G2 路径式入口与 query 式同一套闸门（method 不进签名表）", code_of(res) == "E0000", res)

print("\n== 2. 十一道闸门：位次与边界 ==")
st, res = call(AK1, SK1, "ecx.items.entity.get", drop=("timestamp",))
check("缺 timestamp -> E4001", code_of(res) == "E4001", res)
st, res = call(AK1, SK1, "ecx.items.entity.get", drop=("app_key",))
check("缺 app_key -> E4001", code_of(res) == "E4001", res)
st, res = call(AK1, SK1, "ecx.items.entity.get", drop=("sign",))
check("缺 sign -> E4004（第 4 道先于查密钥：不给没带签的人探密钥）", code_of(res) == "E4004", res)
st, res = call("ak_does_not_exist", SK1, "ecx.items.entity.get")
check("未知 app_key -> E4003", code_of(res) == "E4003", res)
st, res = call(AK1, SK1, "ecx.items.entity.get", sign_override="0" * 32)
check("签错 -> E4004（与缺 sign 同码）", code_of(res) == "E4004", res)
st, res = call(AK1, SK1, "ecx.items.entity.get", extra={"pageSize": "1"}, bad_byte="pageSize")
check("改一个字节 -> E4004", code_of(res) == "E4004", res)
st, res = call(AK1, SK1, "ecx.items.entity.get", ts_offset=601)
check("timestamp +601 -> E4002", code_of(res) == "E4002", res)
st, res = call(AK1, SK1, "ecx.items.entity.get", ts_offset=-601)
check("timestamp -601 -> E4002（边界两个方向都测）", code_of(res) == "E4002", res)
st, res = call(AK1, SK1, "ecx.items.entity.get", ts_offset=599)
check("timestamp +599 仍在窗内", code_of(res) == "E0000", res)
st, res = call(AK1, SK1, "ecx.items.entity.get", version="9.9")
check("未知 version -> E4006", code_of(res) == "E4006", res)
st, res = call(AK1, SK1, "ecx.nope.nope")
check("未知 method -> E4005", code_of(res) == "E4005", res)
st, res = call(AK1, SK1, "ecx.items.entity.get", verb="POST")
check("verb 不符 -> E4005 而不是 405（HTTP 仍 200）", st == 200 and code_of(res) == "E4005", (st, res))
st, res = call(AK1, SK1, "ecx.member.query", version="1.0")
check("method 必填位缺了 -> E4001（第 10 道之后才判参数齐不齐）", code_of(res) == "E4001", res)
st, res = call(AK1, SK1, "ecx.items.entity.get", drop=("nonce",))
check("缺 nonce/request_id -> E4001", code_of(res) == "E4001", res)

# 重放：同一个 nonce 连打两次**写面**，第二次必须挡住且账上只动一次
mtok = login(MEMBER, VCODE, "/api/h5app/wxapp/new_login")
uid = data("GET", "/api/h5app/wxapp/member", token=mtok)["user_id"]
pt0 = data("GET", "/api/h5app/wxapp/point", token=mtok)["point"]
fixed = "REPLAY-" + RUN
st, res = call(AK1, SK1, "ecx.member_point.update", verb="POST", nonce_value=fixed,
               body={"user_id": uid, "change_point": 7, "remark": RUN})
check("首次带 nonce 的动账成功（OA-M14 与 PT-A2 同一条动账口）", code_of(res) == "E0000", res)
pt1 = data("GET", "/api/h5app/wxapp/point", token=mtok)["point"]
st, res = call(AK1, SK1, "ecx.member_point.update", verb="POST", nonce_value=fixed,
               body={"user_id": uid, "change_point": 7, "remark": RUN})
pt2 = data("GET", "/api/h5app/wxapp/point", token=mtok)["point"]
check("同一 nonce 二次打写面：E4002 且余额没再动（挡在委托之前，不是业务幂等兜住）",
      code_of(res) == "E4002" and pt1 == pt0 + 7 and pt2 == pt1, (res, pt0, pt1, pt2))
# 换个 nonce 就该继续能用：挡住第二次的是 nonce，不是"这一笔金额"
st, res = call(AK1, SK1, "ecx.member_point.update", verb="POST",
               body={"user_id": uid, "change_point": -7, "remark": RUN + "-回冲"})
check("换 nonce 后同一条写面仍可执行（并回冲，账回到基线）",
      code_of(res) == "E0000" and data("GET", "/api/h5app/wxapp/point", token=mtok)["point"] == pt0, res)

print("\n== 3. 分发与租户边界 ==")
st, res = call(AK2, SK2, "ecx.items.entity.get")
check("company 2 的密钥读不到 company 1 的商品（0 行而不是报错）",
      code_of(res) == "E0000" and res["data"]["total_count"] == 0, res)
st, res = call(AK2, SK2, "ecx.items.entity.get", {"company_id": "1"})
check("入参写 company_id=1 -> E4001（fail-closed：入参从不决定租户）", code_of(res) == "E4001", res)

oid = data("POST", "/api/h5app/wxapp/order_new",
           {"cart_type": "fastbuy", "order_type": "normal", "address_id": 1,
            "items": [{"item_id": ITEM, "num": 1}]}, mtok)["order_id"]
gw = call(AK1, SK1, "ecx.order.get", {"order_id": str(oid)})[1]
adm = data("GET", "/api/orders/%s" % oid, token=atok)
gi, ai = gw["data"]["orderInfo"], adm["orderInfo"]
check("ecx.order.get 与 ORDER-9 同一条单的键集合完全一致（同一装配函数）",
      code_of(gw) == "E0000" and set(gi) == set(ai), sorted(set(ai) ^ set(gi)))
check("ecx.order.get 的数值字段与后台逐值相同",
      gi["order_id"] == ai["order_id"] and gi["total_fee"] == ai["total_fee"]
      and gi["user_id"] == ai["user_id"] and gi["order_status"] == ai["order_status"],
      (gi["order_id"], ai["order_id"], gi["total_fee"], ai["total_fee"]))
mobile_keys = [k for k in ("mobile", "receiver_mobile") if k in gi]
check("出境面手机号一律掩码（与 datapass.pass 无关）",
      mobile_keys and all("*" in str(gi[k]) and str(gi[k]) != str(ai[k]) for k in mobile_keys),
      [(k, gi[k], ai[k]) for k in mobile_keys])
# OA-M9 只注册在 `1.0`（源系统 `OpenapiRoutes` 就这么分的版，2.0 里没有这一条），
# 用默认 `version="2.0"` 打它会先撞第 10 道闸门的 `E4005 找不到API` —— 那是**版本号**的问题，不是这一条判据
st, res = call(AK1, SK1, "ecx.member.query", {"mobile": MEMBER}, version="1.0")
check("OA-M9 按手机号查会员：出参恒掩码、能拿到 user_id",
      code_of(res) == "E0000" and "*" in str(res["data"]["mobile"]) and str(res["data"]["user_id"]) == str(uid), res)
st, res = call(AK2, SK2, "ecx.member.query", {"mobile": MEMBER}, version="1.0")
check("别租户拿同号会员 -> E5501（不存在与不归你同码：不给探测留回答）", code_of(res) == "E5501", res)
data("POST", "/api/h5app/wxapp/order/cancel", {"order_id": oid, "cancel_reason": "冒烟清理 " + RUN}, mtok)

print("\n== 4. 后台档案面 OP-A1..A6 ==")
lst = data("GET", "/api/setting/openapi/developers", token=atok)
check("OP-A1 列表含 seed 的演示密钥", any(r["app_key"] == AK1 for r in lst["list"]), lst)
row = [r for r in lst["list"] if r["app_key"] == AK1][0]
check("OP-A1 出参没有 app_secret（连列清单都不含这一列）",
      "app_secret" not in row and "external_app_secret" not in row, sorted(row))
newd = data("POST", "/api/setting/openapi/developer",
            {"name": "冒烟对接方 " + RUN, "remark": RUN}, token=atok)
did, dak, dsk = newd["developer_id"], newd["app_key"], newd["app_secret"]
check("OP-A2 服务端发密钥（ak_+16 位 / sk_+32 位）且只有这一次带 secret",
      dak.startswith("ak_") and len(dak) == 19 and dsk.startswith("sk_") and len(dsk) == 35, newd)
st, res = call(dak, dsk, "ecx.items.entity.get")
check("新密钥立刻可用（不排队、不生效延迟）", code_of(res) == "E0000", res)
data("PUT", "/api/setting/openapi/developer/%d" % did,
     {"external_base_uri": "https://erp.invalid/hook", "external_app_key": "ek_x",
      "external_app_secret": "esk_" + RUN}, token=atok)
st, res = call(dak, dsk, "ecx.items.entity.get")
check("OP-A3 改出站三列不影响到站密钥", code_of(res) == "E0000", res)
after3 = [r for r in data("GET", "/api/setting/openapi/developers?keyword=" + dak, token=atok)["list"]
          if r["developer_id"] == did][0]
check("OP-A3 出站地址在后台也只脱敏到 host", after3["external_base_uri"] == "erp.invalid", after3)
reset = data("POST", "/api/setting/openapi/developer/%d/reset_secret" % did, {}, token=atok)
st, res = call(dak, dsk, "ecx.items.entity.get")
check("OP-A4 重置后旧 secret 下一个请求即 E4004（没有宽限期）", code_of(res) == "E4004", res)
st, res = call(dak, reset["app_secret"], "ecx.items.entity.get")
check("OP-A4 新 secret 当场生效", code_of(res) == "E0000", res)


def gw_stats(d):
    return data("GET", "/api/setting/openapi/developer/%d/stats" % d, token=atok)["gateway"]


# 计数口径要**两个方向**都钉：闸门阶段就失败的（错签）不该记在这个开发者头上，
# 认出调用方之后失败在委托里的（E5301）必须记 —— 不然 OP-A6 的 `fail` 位永远是个装饰
g0 = gw_stats(did)
st, res = call(dak, reset["app_secret"], "ecx.items.entity.get", sign_override="0" * 32)
check("换新 secret 之后错签仍是 E4004", code_of(res) == "E4004", res)
g1 = gw_stats(did)
check("闸门阶段就失败的不落在这个开发者头上（它还没被认出来，落 UNKNOWN 桶）", g1 == g0, (g0, g1))
st, res = call(dak, reset["app_secret"], "ecx.order.get", {"order_id": "999999999999999"})
check("签对但单号不是你的 -> E5301（不存在与跨租户同答：归属闸门在委托之前）", code_of(res) == "E5301", res)
g2 = gw_stats(did)
check("委托里的失败计入该开发者：total 与 fail 各 +1、success 不动",
      g2["fail"] == g1["fail"] + 1 and g2["total"] == g1["total"] + 1 and g2["success"] == g1["success"], (g1, g2))
data("PUT", "/api/setting/openapi/developer/%d" % did, {"status": 0}, token=atok)
st, res = call(dak, reset["app_secret"], "ecx.items.entity.get")
check("OP-A3 停用即刻 E4003（吊销不是删行，历史留着）", code_of(res) == "E4003", res)
cat = data("GET", "/api/setting/openapi/methods", token=atok)
methods = sorted({m["method"] for m in cat["list"]})
check("OP-A5 目录 = 注册表的 19 个 method、2 个版本（文档面从代码出）",
      len(methods) == 19 and cat["versions"] == ["1.0", "2.0"], (len(methods), cat["versions"]))
check("OP-A5 每条都带 verb/必填位（对接方照着它写客户端）",
      all(set(m) >= {"id", "version", "method", "verb", "summary", "required", "required_any"} for m in cat["list"]),
      cat["list"][0])
stats = data("GET", "/api/setting/openapi/developer/%d/stats" % did, token=atok)
check("OP-A6 概况：上面那一轮的增量到这里原样数得到（进程内计数、重启归零）",
      stats["gateway"]["total"] == g2["total"] and stats["gateway"]["fail"] == g2["fail"]
      and stats["gateway"]["success"] >= 1 and stats["delivery"]["fail"] == 0 and "note" in stats,
      (stats["gateway"], g2))

print("\n== 5. 订阅面四道 422（WH-A2）==")
st, res = req("POST", "/api/webhook/subscription",
              {"developer_id": 2, "topic": "order.created", "url": RECV_URL}, token=atok)
check("开发者不存在（本租户查不到 company 2 的 developer 2）-> 404", st == 404, (st, res))
st, res = req("POST", "/api/webhook/subscription",
              {"developer_id": 1, "topic": "order.nope", "url": RECV_URL}, token=atok)
check("未知 topic -> 422（配了也永远不来事件，那种对端接受不了）",
      st == 422 and "未知事件" in str(res), (st, res))
st, res = req("POST", "/api/webhook/subscription",
              {"developer_id": 1, "topic": "order.created", "url": "http://erp.example.com/hook"}, token=atok)
check("非 https 且非本机回环 -> 422（明文推 HMAC 载荷等于把签名白送）",
      st == 422 and "https" in str(res), (st, res))
st, res = req("POST", "/api/webhook/subscription",
              {"developer_id": 1, "topic": "order.created", "url": "ftp://127.0.0.1/hook"}, token=atok)
check("非 http/https 的 scheme -> 422", st == 422, (st, res))
no_out = data("POST", "/api/setting/openapi/developer", {"name": "没有出站密钥的 " + RUN}, token=atok)
st, res = req("POST", "/api/webhook/subscription",
              {"developer_id": no_out["developer_id"], "topic": "order.created", "url": RECV_URL}, token=atok)
check("开发者没有 external_app_secret -> 422（让它建成，事件只会堆成一排终态 fail）",
      st == 422 and "出站密钥" in str(res), (st, res))
data("PUT", "/api/setting/openapi/developer/%d" % did, {"status": 1}, token=atok)
sub1 = data("POST", "/api/webhook/subscription",
            {"developer_id": 1, "topic": "order.created", "url": RECV_URL}, token=atok)
st, res = req("POST", "/api/webhook/subscription",
              {"developer_id": 1, "topic": "order.created", "url": RECV_URL}, token=atok)
check("同开发者同 topic 同 url 重复 -> 422", st == 422 and "已存在" in str(res), (st, res))
sub2 = data("POST", "/api/webhook/subscription",
            {"developer_id": 1, "topic": "order.paid", "url": RECV_URL}, token=atok)
# 后三段动作（发货 / 确认收货 / 关单）要的三个 topic。outbox 是"事件 × 订阅"的笛卡尔积，
# 没订阅的事件**一行都不落**（§25.4：连 pending 都不生成），所以这里少建一条，
# 第 6 段那三条判据就变成"断言一个必然为空的集合"——那是假绿，不是通过。
sub3 = data("POST", "/api/webhook/subscription",
            {"developer_id": 1, "topic": "order.shipped", "url": RECV_URL}, token=atok)
sub4 = data("POST", "/api/webhook/subscription",
            {"developer_id": 1, "topic": "order.completed", "url": RECV_URL}, token=atok)
sub5 = data("POST", "/api/webhook/subscription",
            {"developer_id": 1, "topic": "order.closed", "url": RECV_URL}, token=atok)
subs = data("GET", "/api/webhook/subscriptions?developer_id=1", token=atok)
check("WH-A1 列表带出 app_key/developer_name（不只给一个数字）",
      any(r["app_key"] == AK1 and r["developer_name"] for r in subs["list"]), subs["list"][:1])
check("WH-A3 只改状态：status=0 即刻生效、developer_id 不可改",
      data("PUT", "/api/webhook/subscription/%d" % sub2["id"], {"status": 1}, token=atok)["status"] == 1, sub2)

print("\n== 6. 真实事件出站：order.created / order.paid 两层签名都验 ==")
received.clear()
mode["status"] = 200
o2 = data("POST", "/api/h5app/wxapp/order_new",
          {"cart_type": "fastbuy", "order_type": "normal", "address_id": 1,
           "items": [{"item_id": ITEM, "num": 1}]}, mtok)["order_id"]
got = wait_events("order.created")
check("下单后假接收服务器收到 order.created（事务内落 outbox、提交后即刻投）", len(got) >= 1, got[:1])
if got:
    hdr, env = got[0]
    raw = raw_hits("order.created")[0][1]
    check("四个头齐且与信封一致",
          hdr.get("x-ecx-event") == "order.created" and hdr.get("x-ecx-msg-id") == env["msg_id"]
          and hdr.get("x-ecx-app-key") == OUT_AK1 and hdr.get("x-ecx-timestamp"), hdr)
    check("第一层：X-Ecx-Signature 覆盖**原始字节**（重序列化就永远验不过）",
          hdr.get("x-ecx-signature") == "sha256=" + hmac_hex(OUT_SK1, hdr["x-ecx-timestamp"], raw),
          hdr.get("x-ecx-signature"))
    check("第二层：体内 sign 按 assemble 语义验通（PHP 形态接收端不改代码就能验）",
          env.get("sign") == gen_sign(env, OUT_SK1), env.get("sign"))
    check("信封六键、租户与归属来自密钥行",
          set(env) == {"msg_id", "topic", "company_id", "developer_id", "occurred_at", "data", "sign"}
          and env["company_id"] == 1 and env["developer_id"] == 1, sorted(env))
    check("order.created 载荷字段逐个对（§25.4 那张表）",
          set(env["data"]) == {"order_id", "user_id", "distributor_id", "shop_id", "pay_fee", "status", "created"},
          sorted(env["data"]))
    check("事件里的单号就是刚下的那一单", str(env["data"]["order_id"]) == str(o2), env["data"])

t = data("POST", "/api/h5app/wxapp/payment", {"order_id": o2, "pay_type": "wxpay"}, mtok)
fee = data("GET", "/api/h5app/wxapp/order/%s" % o2, token=mtok)["orderInfo"]["total_fee"]
fields = urllib.parse.urlencode({"out_trade_no": t["trade_id"], "total_fee": str(fee),
                                 "trade_state": "SUCCESS", "transaction_id": "M21-" + str(o2)[-6:]})
st, cb = req("POST", "/api/notify/wxpay", raw_body=fields.encode(), ctype="application/x-www-form-urlencoded")
check("回调结清（mock 渠道）", "SUCCESS" in str(cb), (st, cb))
got_paid = wait_events("order.paid")
check("order.paid 到达且字段逐个对（挂点在 CAS 命中那一支）",
      len(got_paid) == 1 and set(got_paid[0][1]["data"]) == {"order_id", "pay_fee", "pay_type", "pay_time"}
      and str(got_paid[0][1]["data"]["order_id"]) == str(o2)
      and got_paid[0][1]["data"]["pay_fee"] == fee, got_paid[:1])
st, cb = req("POST", "/api/notify/wxpay", raw_body=fields.encode(), ctype="application/x-www-form-urlencoded")
time.sleep(1.5)
check("重复回调（CAS 未命中）不生成第二条 order.paid",
      "SUCCESS" in str(cb) and len(envelopes("order.paid")) == 1, len(envelopes("order.paid")))
# 结清之后走一遍发货 → 收货，把 order.shipped / order.completed 两条也验掉
st, dl = req("PUT", "/api/orders/delivery", {"order_id": o2, "delivery_corp": "zto", "delivery_code": "SF" + RUN}, atok)
got_ship = wait_events("order.shipped", timeout=10)
check("order.shipped 随发货到达（含 orders_delivery_id 与 delivered 位）",
      st == 200 and len(got_ship) == 1
      and set(got_ship[0][1]["data"]) == {"order_id", "orders_delivery_id", "delivery_corp", "delivery_code", "delivered"},
      (st, got_ship[:1]))
data("POST", "/api/h5app/wxapp/order/confirmReceipt", {"order_id": o2}, mtok)
got_done = wait_events("order.completed", timeout=10)
check("order.completed 随确认收货到达（outbox 与迁移同生死：done 才算完成）",
      len(got_done) == 1 and str(got_done[0][1]["data"]["order_id"]) == str(o2)
      and set(got_done[0][1]["data"]) == {"order_id", "finish_time"}, got_done[:1])
# 关单事件：另下一单再取消，断 order.closed
o4 = data("POST", "/api/h5app/wxapp/order_new",
          {"cart_type": "fastbuy", "order_type": "normal", "address_id": 1,
           "items": [{"item_id": ITEM, "num": 1}]}, mtok)["order_id"]
data("POST", "/api/h5app/wxapp/order/cancel", {"order_id": o4, "cancel_reason": "冒烟关单 " + RUN}, mtok)
got_close = wait_events("order.closed", timeout=10)
check("order.closed 随用户取消到达（reason/closed_at 齐）",
      len(got_close) == 1 and set(got_close[0][1]["data"]) == {"order_id", "reason", "closed_at"}
      and str(got_close[0][1]["data"]["order_id"]) == str(o4), got_close[:1])

print("\n== 7. 失败退避与手动重推（WH-A5/A6/A7/A9）==")
mode["status"] = 500
te = data("POST", "/api/webhook/test",
          {"url": RECV_URL, "developer_id": 1, "topic": "webhook.test", "ping": RUN}, token=atok)
check("WH-A9 测试事件即刻投过一轮：500 算可重试失败、状态回 pending",
      te["status"] == "pending" and te["last_http_status"] == 500 and te["dispatched"] is True, te)
d = data("GET", "/api/webhook/delivery/%d" % te["id"], token=atok)
check("WH-A6 详情出的是**当次实际发出**的签名头（不是拿当前密钥重算的值）",
      d["attempts"] == 1 and d["signature"].startswith("sha256=") and d["signature_ts"] > 0,
      (d["attempts"], d["signature"]))
check("WH-A9 入参原样进 data.echo；库里那份不含 sign（签在发送时现算）",
      d["payload"]["data"]["echo"]["ping"] == RUN and "sign" not in d["payload"], d["payload"])
backoff = d["next_try_at"] - int(time.time())
check("退避排到 2^1 x 30 秒附近（不设上限会排到几天后，那种记录等于没有）", 0 < backoff <= 65, backoff)
# 留痕与实发必须逐字相同：这一条才是"WH-A6 出的是当次值"的真正判据 ——
# 拿当前密钥重算也能算出一个"看起来对"的头，只有对端收到的那一个不能骗人
te_hits = [h for h, r in raw_hits("webhook.test") if h.get("x-ecx-msg-id") == te["msg_id"]]
check("WH-A6 留痕的头与接收端当次收到的头逐字相同（signature + signature_ts 两位都对）",
      te_hits and d["signature"] == te_hits[0].get("x-ecx-signature")
      and str(d["signature_ts"]) == te_hits[0].get("x-ecx-timestamp"),
      (d["signature"], te_hits[0].get("x-ecx-signature") if te_hits else None, d["signature_ts"]))
mode["status"] = 200
# 这一条现在是 `pending`（退避到 2^1×30=60 秒之后），手动重推必须 422 —— 重推会让它排两次队。
# 真服务上 `fail` 要投满 `max-attempts=5` 才到（60+120+240+480 秒），脚本不等终态：
# "终态 fail → WH-A7 手动重推、同 msg_id 再打一次"由 M21EndToEndTest @Order(17) 把 max-attempts 压到 2 来证，
# 这里换一条内存测试给不了的断言：**这一条交给真实的 30 秒作业，没人点按钮它也得自己走通**。
st, res = req("POST", "/api/webhook/delivery/%d/retry" % te["id"], {}, atok)
check("pending 的记录手动重推 422（会排两次队；判据在 resetForRetry 那条 SQL 的 status IN 里）",
      st == 422 and "重推" in str(res), (st, res))
waited = wait_delivery(te["id"], "success")
check("退避到期后由真实作业投成功：attempts 走到 2、msg_id 不变（对端靠它判重）",
      waited["status"] == "success" and waited["attempts"] == 2 and waited["msg_id"] == te["msg_id"], waited)
rt2 = req("POST", "/api/webhook/delivery/%d/retry" % te["id"], {}, atok)
check("已成功的记录不能再重推（422，而不是'提交了但什么都没发生'）",
      rt2[0] == 422 and "重推" in str(rt2[1]), rt2)
dl = data("GET", "/api/webhook/deliveries?msg_id=%s" % te["msg_id"], token=atok)
check("WH-A5 按 msg_id 精确回捞到那一条（列表不含正文）",
      len(dl["list"]) == 1 and dl["list"][0]["status"] == "success" and "payload" not in dl["list"][0], dl)
d2 = data("GET", "/api/webhook/delivery/%d" % te["id"], token=atok)
check("重投不换 msg_id，正文里的 msg_id 与行上一致",
      d2["msg_id"] == te["msg_id"] and d2["payload"]["msg_id"] == te["msg_id"], d2["msg_id"])
check("两次投递都真打到了接收端：同一个 msg_id 在接收端有两份原始包（500 那一次与作业那一次）",
      len([h for h, r in raw_hits("webhook.test") if h.get("x-ecx-msg-id") == te["msg_id"]]) == 2,
      [h.get("x-ecx-msg-id") for h, r in raw_hits("webhook.test")])
mode["reset_next"] = True
dead = data("POST", "/api/webhook/test", {"url": RECV_URL, "developer_id": 1, "topic": "webhook.test"}, token=atok)
check("对端直接断开（没收到任何应答）同样算可重试失败：状态位 0",
      dead["status"] == "pending" and dead["last_http_status"] == 0 and dead["last_error"], dead)
dd = data("GET", "/api/webhook/delivery/%d" % dead["id"], token=atok)
check("连接失败的那一条排到了下一次（由 30 秒的作业接手，不进终态、也**不能**手动重推）",
      dd["status"] == "pending" and dd["next_try_at"] > int(time.time()), (dd["status"], dd["last_error"]))
st, res = req("POST", "/api/webhook/delivery/%d/retry" % dead["id"], {}, token=atok)
check("对端断开的那条同样不能手动重推：它已经排好队，再点一次就是排两次队", st == 422, (st, res))
test_env = [e for h, e in envelopes("webhook.test")]
check("测试事件与正式推送同一套签名（体内 sign 验通）",
      test_env and all(e.get("sign") == gen_sign(e, OUT_SK1) for e in test_env), len(test_env))
flt = req("GET", "/api/webhook/deliveries?status=nope", token=atok)
check("投递状态筛不认识的取值直接拒、且把可选值写在 message 里（不当'全出'处理）",
      flt[0] == 400 and "pending" in str(flt[1]), flt)
bad_time = req("GET", "/api/webhook/deliveries?created_start=abc", token=atok)
check("时间段筛给的是脏串 -> 400 并说明两种可接受写法（C191：读口入参形状归 400）",
      bad_time[0] == 400 and "unix 秒" in str(bad_time[1]), bad_time)
rng = data("GET", "/api/webhook/deliveries?created_start=%d&created_end=%d" % (int(time.time()) - 3600, int(time.time()) + 60), token=atok)
check("WH-A5 时间段筛（unix 秒）与 count/page 同口径", rng["total_count"] >= len(rng["list"]), rng["total_count"])

print("\n== 8. 停用即刻断推 + 权限面 + 收尾 ==")
data("PUT", "/api/webhook/subscription/%d" % sub1["id"], {"status": 0}, token=atok)
o3 = data("POST", "/api/h5app/wxapp/order_new",
          {"cart_type": "fastbuy", "order_type": "normal", "address_id": 1,
           "items": [{"item_id": ITEM, "num": 1}]}, mtok)["order_id"]
time.sleep(1.5)
check("订阅 status=0 之后新事件连 pending 都不生成（不是生成后再跳过）",
      all(str(e["data"]["order_id"]) != str(o3) for _, e in envelopes("order.created"))
      and not data("GET", "/api/webhook/deliveries?topic=order.created&status=pending", token=atok)["list"],
      o3)
data("POST", "/api/h5app/wxapp/order/cancel", {"order_id": o3, "cancel_reason": "冒烟清理 " + RUN}, mtok)
stok = login("distributor01", SPW)
st, res = req("GET", "/api/setting/openapi/developers", token=stok)
check("role 2 无 openapi.lists（§25.5：一条都不配）", st == 403, (st, res))
st, res = req("GET", "/api/webhook/subscriptions", token=stok)
check("role 2 无 webhook.lists", st == 403, (st, res))
st, res = req("POST", "/api/webhook/test", {"url": RECV_URL, "developer_id": 1}, token=stok)
check("role 2 无 webhook.push（往外打流量这一位不给店铺运营）", st == 403, (st, res))
st, res = req("GET", "/api/openapi?app_key=%s" % AK1)
check("网关那条匿名链不吃令牌：没签也一样进闸门（E4001 而不是 401）", st == 200 and code_of(res) == "E4001", (st, res))
st, res = req("DELETE", "/api/webhook/subscription/%d" % sub1["id"], token=atok)
check("WH-A4 删订阅但保留投递记录", st == 200 and res["data"]["delivery_log_kept"] is True, res)
kept = data("GET", "/api/webhook/deliveries?developer_id=1", token=atok)
check("删完订阅，证据还在（订阅是配置，投递记录不是）", kept["total_count"] >= 6, kept["total_count"])
tops = data("GET", "/api/webhook/topics", token=atok)
check("WH-A8 目录十条、每条带挂点与载荷字段说明",
      len(tops) == 10 and all(set(t) == {"topic", "summary", "hook", "data_fields"} for t in tops), len(tops))
# 收尾把这一轮建的订阅**都删掉**（不只停用）：服务在这一轮之后还要常驻跑别的冒烟，
# 留着启用中的订阅 = 别人每下一单就往这个已经关掉的 18099 端口推一次、退避、进终态 fail，
# 那些行既不是任何人的事件，又把 `webhook_delivery_log` 堆成噪音。投递记录按 WH-A4 留着。
for s in (sub2, sub3, sub4, sub5):
    req("DELETE", "/api/webhook/subscription/%d" % s["id"], token=atok)
data("PUT", "/api/setting/openapi/developer/%d" % did, {"status": 0}, token=atok)

srv.shutdown()
n_ok = sum(ok)
print("\n[M2-1 冒烟 %s] 通过 %d/%d" % (RUN, n_ok, len(ok)))
raise SystemExit(0 if n_ok == len(ok) else 1)
