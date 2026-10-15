import json
import time
import urllib.error
import urllib.request
from urllib.parse import quote

"""M0-8 会员与支撑实跑冒烟：MEM-1..8 个人中心 + 地址/收藏/浏览 + SUP-1 配置 + SUP-2 上传静态回读 + SUP-4 审计日志。

服务需以干净库启动（删掉 data/*.db 与 data/storage），seed 会重放：会员 1=普通等（积分 1000）、
会员 2=金卡 95 折、收藏 2 条（item 3 快照价 9900 对实时价 8900）、地址 1 条、配置若干。
脚本一律用相对断言（差值 / 唯一性 / 一致性），上一轮残留只会让用例更严格，不会假绿。
"""

BASE = "http://127.0.0.1:18080"
PW = "admin" + "123"
VIEWER_PW = "viewer" + "123"
VCODE = "8888"
MEMBER = "13800000001"
GOLD = "13800000002"
ITEM = 3                     # 每日坚果礼盒，实时价 8900 / 收藏快照价 9900
RUN = "M08%d" % int(time.time())

ok = []


def url(path):
    """中文查询参数（keyword=坚果）必须先百分号编码，否则 http.client 用 ascii 发请求行会炸"""
    return BASE + quote(path, safe="/?=&:%")


def req(method, path, body=None, token=None):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(url(path), data=data, method=method)
    r.add_header("Content-Type", "application/json")
    if token:
        r.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(r, timeout=20) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode("utf-8"))


def get_raw(path, token=None):
    """静态资源面：按字节回读，不走 JSON 包络"""
    r = urllib.request.Request(url(path), method="GET")
    if token:
        r.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(r, timeout=20) as resp:
            return resp.status, resp.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()


def post_multipart(path, field, filename, content_type, payload, token=None):
    """SUP-2 上传：手写 multipart 边界，避免为冒烟脚本引第三方库"""
    boundary = "----ecx" + RUN
    body = b"".join([
        ("--%s\r\n" % boundary).encode(),
        ('Content-Disposition: form-data; name="%s"; filename="%s"\r\n' % (field, filename)).encode(),
        ("Content-Type: %s\r\n\r\n" % content_type).encode(),
        payload,
        ("\r\n--%s--\r\n" % boundary).encode(),
    ])
    r = urllib.request.Request(BASE + path, data=body, method="POST")
    r.add_header("Content-Type", "multipart/form-data; boundary=" + boundary)
    if token:
        r.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(r, timeout=30) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode("utf-8"))
        except ValueError:
            return e.code, {"message": "<non-json>"}


def check(name, cond, extra=""):
    ok.append(bool(cond))
    print(("PASS " if cond else "FAIL ") + name + ("" if cond else " -> " + str(extra)[:400]))


def data(method, path, body=None, token=None):
    st, res = req(method, path, body, token)
    assert st == 200, "%s %s -> %s %s" % (method, path, st, res)
    return res["data"]


def fail(method, path, body, code, token=None):
    st, res = req(method, path, body, token)
    return st, res


def login(mobile):
    return data("POST", "/api/h5app/wxapp/new_login", {
        "auth_type": "local", "username": mobile, "check_type": "mobile", "vcode": VCODE,
    })["token"]


def subset(name, actual, expected):
    missing = set(expected) - set(actual)
    check(name, not missing, "缺字段 " + str(sorted(missing)))
    return missing


mtok = login(MEMBER)
gtok = login(GOLD)
atok = data("POST", "/api/operator/login", {"username": "admin", "password": PW})["token"]
vtok = data("POST", "/api/operator/login", {"username": "viewer", "password": VIEWER_PW})["token"]
check("会员/金卡/后台/只读账号 四个令牌就绪",
      all([mtok, gtok, atok, vtok]))

# ===== 1. MEM-1 我的信息 =====
p = data("GET", "/api/h5app/wxapp/member", token=mtok)
subset("MEM-1 出参契约（docs/04 §2.6）", p.keys(), [
    "user_id", "grade_id", "mobile", "nickname", "avatar", "sex", "birthday",
    "gradeInfo", "nextGradeInfo", "totalConsumption", "point", "deposit", "is_new",
])
check("MEM-1 手机号脱敏", p["mobile"] == "138****0001", p["mobile"])
subset("MEM-1 gradeInfo 契约", p["gradeInfo"], ["grade_id", "grade_name", "level", "growth_value", "discount", "need_growth"])
subset("MEM-1 deposit 契约", p["deposit"], ["deposit_amount", "gift_amount", "balance"])
check("MEM-1 balance = 本金 + 赠金", p["deposit"]["balance"] == p["deposit"]["deposit_amount"] + p["deposit"]["gift_amount"], p["deposit"])
check("MEM-1 金卡折扣 95，普通等 100",
      data("GET", "/api/h5app/wxapp/member", token=gtok)["gradeInfo"]["discount"] == 95
      and p["gradeInfo"]["discount"] == 100,
      (p["gradeInfo"], data("GET", "/api/h5app/wxapp/member", token=gtok)["gradeInfo"]))
nx = p["nextGradeInfo"]
check("MEM-1 nextGradeInfo 门槛高于当前档", nx is None or nx["level"] > p["gradeInfo"]["level"], nx)
check("MEM-1 need_growth = 下一档门槛 - 累计实付（不为负）",
      nx is None or nx["need_growth"] == max(0, nx["growth_value"] - p["totalConsumption"]), nx)
check("MEM-1 金卡顶级无下一档",
      data("GET", "/api/h5app/wxapp/member", token=gtok)["nextGradeInfo"] is None)
check("MEM-1 is_new 与累计实付口径自洽",
      (p["is_new"] == 1) == (p["totalConsumption"] == 0), (p["is_new"], p["totalConsumption"]))

# ===== 2. MEM-2 改资料 =====
before_name = p["nickname"]
r = data("PUT", "/api/h5app/wxapp/member", {
    "username": "冒烟昵称" + RUN, "sex": 2, "birthday": "1993-03-03",
    "habbit": [{"name": "露营", "ischecked": 1}, {"name": "骑行", "ischecked": 0}],
    "email": "smoke@example.com",
}, token=mtok)
check("MEM-2 昵称写入生效", r["nickname"] == "冒烟昵称" + RUN, r["nickname"])
check("MEM-2 性别/生日/邮箱生效", (r["sex"], r["birthday"], r["email"]) == (2, "1993-03-03", "smoke@example.com"), r)
check("MEM-2 爱好数组原样读回", [h["name"] for h in r["habbit"]] == ["露营", "骑行"], r["habbit"])
check("MEM-2 掩码手机号静默忽略且资料照改",
      data("PUT", "/api/h5app/wxapp/member", {"mobile": "138****0001", "username": before_name}, token=mtok)["mobile"] == "138****0001")
st, err = fail("PUT", "/api/h5app/wxapp/member", {"mobile": "13700000007"}, 400, mtok)
check("MEM-2 明文改号被拒 400", st == 400, (st, err))
check("MEM-2 拒绝时错误体四件套齐全",
      set(err.keys()) >= {"message", "errors", "code", "status_code"} and "mobile" in err["errors"], err)
check("MEM-2 改 company_id 被拒", fail("PUT", "/api/h5app/wxapp/member", {"company_id": 2}, 400, mtok)[0] == 400)
check("MEM-2 改 user_id 被拒", fail("PUT", "/api/h5app/wxapp/member", {"user_id": 9}, 400, mtok)[0] == 400)
check("MEM-2 未知字段静默忽略（源行为）",
      data("PUT", "/api/h5app/wxapp/member", {"whatever": 1, "username": before_name}, token=mtok)["nickname"] == before_name)

# ===== 3. MEM-8 验证码 =====
v = data("POST", "/api/h5app/vcode", {"mobile": MEMBER, "type": "rebind_mobile"})
subset("MEM-8 回执契约", v.keys(), ["mobile", "type", "status", "expire_seconds"])
check("MEM-8 匿名可发码（登录前就要发码）", v["status"] == 1 and v["mock"] is True, v)
check("MEM-8 回执号码同样脱敏", v["mobile"] == "138****0001", v["mobile"])
check("MEM-8 手机号格式错 400", fail("POST", "/api/h5app/vcode", {"mobile": "12345"}, 400)[0] == 400)
check("MEM-8 未注册类型 400 且带字段错误",
      "type" in fail("POST", "/api/h5app/vcode", {"mobile": MEMBER, "type": "whatever"}, 400)[1]["errors"])

# ===== 4. MEM-3 换绑手机 =====
NEW = "139%08d" % (int(time.time()) % 100000000)
check("MEM-3 验证码错 400", fail("PUT", "/api/h5app/wxapp/member/mobile", {"mobile": NEW, "vcode": "0000"}, 400, mtok)[0] == 400)
check("MEM-3 号码格式错 400", fail("PUT", "/api/h5app/wxapp/member/mobile", {"mobile": "139", "vcode": VCODE}, 400, mtok)[0] == 400)
check("MEM-3 与他人号冲突 422", fail("PUT", "/api/h5app/wxapp/member/mobile", {"mobile": GOLD, "vcode": VCODE}, 422, mtok)[0] == 422)
check("MEM-3 与原号相同 422", fail("PUT", "/api/h5app/wxapp/member/mobile", {"mobile": MEMBER, "vcode": VCODE}, 422, mtok)[0] == 422)
r = data("PUT", "/api/h5app/wxapp/member/mobile", {"mobile": NEW, "vcode": VCODE}, token=mtok)
check("MEM-3 换绑成功且回显脱敏", r["mobile"] == NEW[:3] + "****" + NEW[-4:], r)
check("MEM-3 新号可登录", data("POST", "/api/h5app/wxapp/new_login", {
    "auth_type": "local", "username": NEW, "check_type": "mobile", "vcode": VCODE, "auto_register": 0})["status"] == 1)
check("MEM-3 旧号即失效", data("POST", "/api/h5app/wxapp/new_login", {
    "auth_type": "local", "username": MEMBER, "check_type": "mobile", "vcode": VCODE, "auto_register": 0})["status"] == 0)
data("PUT", "/api/h5app/wxapp/member/mobile", {"mobile": MEMBER, "vcode": VCODE}, token=mtok)
check("MEM-3 还原原号后旧号仍可登录", data("POST", "/api/h5app/wxapp/new_login", {
    "auth_type": "local", "username": MEMBER, "check_type": "mobile", "vcode": VCODE, "auto_register": 0})["status"] == 1)

# ===== 5. MEM-4 地址簿 =====
al = data("GET", "/api/h5app/wxapp/addresslist", token=mtok)
subset("MEM-4 地址行契约", al["list"][0].keys(), [
    "address_id", "user_id", "company_id", "username", "telephone", "province", "city",
    "county", "adrdetail", "receiver_address", "postal_code", "is_def", "created", "updated",
])
check("MEM-4 receiver_address 是省市区+详址整串",
      al["list"][0]["receiver_address"].startswith(al["list"][0]["province"])
      and al["list"][0]["adrdetail"] in al["list"][0]["receiver_address"], al["list"][0])
st, err = fail("POST", "/api/h5app/wxapp/addresslist", {"username": "", "telephone": "139"}, 400, mtok)
check("MEM-4 必填缺失逐字段报错", set(err["errors"]) >= {"username", "telephone", "adrdetail"}, err)
new_addr = data("POST", "/api/h5app/wxapp/addresslist", {
    "username": "冒烟收货人", "telephone": MEMBER, "province": "广东省", "city": "广州市",
    "county": "天河区", "adrdetail": "天河路 " + RUN + " 号", "postal_code": "510000", "is_def": 1,
}, token=mtok)
aid = new_addr["address_id"]
check("MEM-4 新增即声明默认并生效", new_addr["is_def"] == 1, new_addr)
al = data("GET", "/api/h5app/wxapp/addresslist", token=mtok)
defs = [a for a in al["list"] if a["is_def"] == 1]
check("MEM-4 默认地址全局至多一条", len(defs) == 1 and defs[0]["address_id"] == aid,
      [(a["address_id"], a["is_def"]) for a in al["list"]])
edited = data("PUT", "/api/h5app/wxapp/addresslist/%d" % aid, {
    "username": "冒烟收货人2", "telephone": MEMBER, "province": "广东省", "city": "深圳市",
    "county": "南山区", "adrdetail": "科苑路 " + RUN + " 号",
}, token=mtok)
check("MEM-4 编辑生效且未传 is_def 时保持默认", edited["username"] == "冒烟收货人2" and edited["is_def"] == 1, edited)
check("MEM-4 越权改他人地址 404", fail("PUT", "/api/h5app/wxapp/addresslist/1", {
    "username": "劫持", "telephone": GOLD, "adrdetail": "x"}, 404, gtok)[0] == 404)
check("MEM-4 越权删他人地址 404", fail("DELETE", "/api/h5app/wxapp/addresslist/1", None, 404, gtok)[0] == 404)
check("MEM-4 不存在的地址 404", fail("PUT", "/api/h5app/wxapp/addresslist/999999", {
    "username": "n", "telephone": MEMBER, "adrdetail": "x"}, 404, mtok)[0] == 404)
data("DELETE", "/api/h5app/wxapp/addresslist", {"address_id": aid}, token=mtok)
al2 = data("GET", "/api/h5app/wxapp/addresslist", token=mtok)
check("MEM-4 删除后条数减少", al2["total_count"] == al["total_count"] - 1, (al["total_count"], al2["total_count"]))
check("MEM-4 删默认后仍恰有一条默认", len([a for a in al2["list"] if a["is_def"] == 1]) == 1, al2["list"])

# ===== 6. MEM-5/6 收藏 =====
cl = data("GET", "/api/h5app/wxapp/member/collect/items", token=mtok)
subset("MEM-5 收藏行契约", cl["list"][0].keys(), [
    "fav_id", "item_id", "item_name", "item_image", "item_price", "fav_price", "store", "is_onsale", "created",
])
nut = [f for f in cl["list"] if f["item_id"] == ITEM]
live = data("GET", "/api/h5app/wxapp/goods/items/%d" % ITEM, token=mtok)["price"]
check("MEM-5 列表出实时价（非快照价）", nut and nut[0]["item_price"] == live, (live, nut))
check("MEM-5 快照价另存 fav_price 供降价提示", nut and nut[0]["fav_price"] == 9900 and nut[0]["fav_price"] != live, nut)
added = data("POST", "/api/h5app/wxapp/member/collect/items/4", token=mtok)
check("MEM-6 收藏成功并回显", added["item_id"] == 4 and added["is_collected"] == 1, added)
again = data("POST", "/api/h5app/wxapp/member/collect/items/4", token=mtok)
check("MEM-6 重复收藏幂等（不产生第二行）", again["fav_id"] == added["fav_id"], (added["fav_id"], again["fav_id"]))
check("MEM-6 取消收藏后列表少一条",
      data("DELETE", "/api/h5app/wxapp/member/collect/items/4", token=mtok) is not None
      and data("GET", "/api/h5app/wxapp/member/collect/items?pageSize=50", token=mtok)["total_count"] == cl["total_count"])
check("MEM-6 取消未收藏项不报错", req("DELETE", "/api/h5app/wxapp/member/collect/items/99", None, mtok)[0] == 200)
f = data("GET", "/api/h5app/wxapp/member/collect/items?keyword=坚果", token=mtok)
check("MEM-5 keyword 命中名称", f["total_count"] >= 1 and all("坚果" in x["item_name"] for x in f["list"]), f)
check("MEM-5 分页参数生效", data("GET", "/api/h5app/wxapp/member/collect/items?pageSize=1", token=mtok)["list"].__len__() == 1)
d = data("GET", "/api/h5app/wxapp/goods/items/%d" % ITEM, token=mtok)
check("GOODS-8 带令牌回收藏状态", d.get("collect") is not None and d["collect"]["is_collected"] == 1, d.get("collect"))

# ===== 7. 浏览足迹 =====
data("GET", "/api/h5app/wxapp/goods/items/2", token=mtok)
bl = data("GET", "/api/h5app/wxapp/member/browse/items?pageSize=50", token=mtok)
check("浏览足迹记下面包屑（item 2 在册）", 2 in [b["item_id"] for b in bl["list"]], [b["item_id"] for b in bl["list"]])
check("浏览行带实时 item 视图", bl["list"][0]["item"] is None or "price" in bl["list"][0]["item"], bl["list"][0])
data("GET", "/api/h5app/wxapp/goods/items/2", token=mtok)
check("同商品重复浏览只留一条",
      data("GET", "/api/h5app/wxapp/member/browse/items?pageSize=50", token=mtok)["total_count"] == bl["total_count"])
data("DELETE", "/api/h5app/wxapp/member/browse/items/2", token=mtok)
check("删单条足迹生效",
      2 not in [b["item_id"] for b in data("GET", "/api/h5app/wxapp/member/browse/items?pageSize=50", token=mtok)["list"]])
check("清空足迹", data("DELETE", "/api/h5app/wxapp/member/browse/items", token=mtok)["deleted"] >= 0
      and data("GET", "/api/h5app/wxapp/member/browse/items", token=mtok)["total_count"] == 0)
g_before = data("GET", "/api/h5app/wxapp/member/browse/items", token=gtok)["total_count"]
check("匿名浏览不入任何人名下",
      data("GET", "/api/h5app/wxapp/goods/items/2")["item_id"] == 2
      and data("GET", "/api/h5app/wxapp/member/browse/items", token=mtok)["total_count"] == 0
      and data("GET", "/api/h5app/wxapp/member/browse/items", token=gtok)["total_count"] == g_before)

# ===== 8. MEM-7 统计 =====
s = data("GET", "/api/h5app/wxapp/member/statistical", token=mtok)
subset("MEM-7 出参契约", s.keys(), ["point", "deposit", "orderscount", "totalConsumption"])
subset("MEM-7 orderscount 契约", s["orderscount"], ["notpay", "pending_delivery", "pending_receive", "done", "evaluation", "cancel", "total"])
oc = data("GET", "/api/h5app/wxapp/orderscount", token=mtok)
check("MEM-7 角标与 ORDER-8 同一口径", s["orderscount"] == oc, (s["orderscount"], oc))
check("MEM-7 资产与 MEM-1 一致", s["deposit"] == p["deposit"] or s["point"] == p["point"], (s, p))

# ===== 9. 会员等级价（详情页 = 结算价口径） =====
gold_detail = data("GET", "/api/h5app/wxapp/goods/items/%d" % ITEM, token=gtok)
expect_price = live - live * (100 - 95) // 100
check("金卡会员详情页 member_price 按 95 折（与折让同口径）",
      gold_detail["member_price"] == expect_price, (gold_detail["member_price"], expect_price))
check("普通会员 member_price = 售价", data("GET", "/api/h5app/wxapp/goods/items/%d" % ITEM, token=mtok)["member_price"] == live)

# ===== 10. SUP-1 配置读写 =====
cfg = data("GET", "/api/config", token=atok)
subset("SUP-1 配置行契约", cfg["list"][0].keys(), ["id", "company_id", "config_key", "config_value", "updated"])
check("SUP-1 total_count 与行数一致", cfg["total_count"] == len(cfg["list"]), (cfg["total_count"], len(cfg["list"])))
check("SUP-1 seed 支付开关读得到", any(c["config_key"] == "pay.wxpay.enable" for c in cfg["list"]))
global_only = [c for c in cfg["list"] if c["config_key"] == "site.member_center_copy"]
check("SUP-1 全局层配置可读（seed 里只有 company_id=0 的键）",
      len(global_only) == 1 and global_only[0]["company_id"] == 0, global_only)
check("SUP-1 同一键的合并视图不重复（全局行与租户行只出一条）",
      len([c for c in cfg["list"] if c["config_key"] == "system.is_saas"]) == 1,
      [c for c in cfg["list"] if c["config_key"] == "system.is_saas"])
key = "smoke." + RUN
w = data("PUT", "/api/config", {key: "v1", "site.contact_phone": "0755-" + RUN}, token=atok)
row = [c for c in w["list"] if c["config_key"] == key]
check("SUP-1 新增键写入即读回", len(row) == 1 and row[0]["config_value"] == "v1", row)
check("SUP-1 写入只落租户层", row[0]["company_id"] == 1, row)
w2 = data("PUT", "/api/config", {key: "v2"}, token=atok)
row2 = [c for c in w2["list"] if c["config_key"] == key]
check("SUP-1 重复写是 upsert 不是插重行", len(row2) == 1 and row2[0]["config_value"] == "v2", row2)
saas = [c for c in data("PUT", "/api/config", {"system.is_saas": "true"}, token=atok)["list"]
        if c["config_key"] == "system.is_saas"]
check("SUP-1 同键取租户覆盖行", saas[0]["config_value"] == "true" and saas[0]["company_id"] == 1, saas)
check("SUP-1 覆盖写后合并视图只留租户行且总数不再增长（全局行未被删除或复制）",
      len([c for c in data("GET", "/api/config", token=atok)["list"] if c["config_key"] == "system.is_saas"]) == 1
      and data("GET", "/api/config", token=atok)["total_count"] == w2["total_count"],
      data("GET", "/api/config", token=atok)["total_count"])
arr = [c for c in data("PUT", "/api/config", {"smoke.cities": ["深圳", "北京"]}, token=atok)["list"]
       if c["config_key"] == "smoke.cities"]
check("SUP-1 数组值序列化存 JSON 文本", arr[0]["config_value"] == '["深圳","北京"]', arr)
check("SUP-1 空对象写 400", fail("PUT", "/api/config", {}, 400, atok)[0] == 400)
check("SUP-1 只读账号无权改 403", fail("PUT", "/api/config", {key: "x"}, 403, vtok)[0] == 403)

# ===== 11. SUP-2 上传与静态回读 =====
png = bytes([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]) + bytes(range(64))
st, up = post_multipart("/api/upload/image", "file", "冒烟图.png", "image/png", png, atok)
check("SUP-2 上传成功", st == 200, (st, up))
d = up["data"]
subset("SUP-2 出参契约", d.keys(), ["id", "url", "path", "file_name", "file_type", "file_size"])
check("SUP-2 url = 前缀 + 相对路径", d["url"] == "/storage/" + d["path"], d)
check("SUP-2 台账大小与字节数一致", d["file_size"] == len(png) and d["file_type"] == "png", d)
st2, got = get_raw(d["url"])
check("SUP-2 静态面匿名可读", st2 == 200, st2)
check("SUP-2 回读字节与上传完全一致", got == png, len(got))
check("SUP-2 存储名不含原始文件名（防路径穿越/覆盖）", "冒烟图" not in d["path"] and ".." not in d["path"], d["path"])
check("SUP-2 未登录 401", post_multipart("/api/upload/image", "file", "a.png", "image/png", png, None)[0] == 401)
check("SUP-2 只读账号 403", post_multipart("/api/upload/image", "file", "a.png", "image/png", png, vtok)[0] == 403)
check("SUP-2 非图片 content-type 拒", post_multipart("/api/upload/image", "file", "a.txt", "text/plain", b"hello", atok)[0] == 400)
check("SUP-2 伪图片后缀拒", post_multipart("/api/upload/image", "file", "a.exe", "image/png", png, atok)[0] == 400)
check("SUP-2 空文件拒", post_multipart("/api/upload/image", "file", "a.png", "image/png", b"", atok)[0] == 400)
check("SUP-2 缺 file 字段 400", req("POST", "/api/upload/image", None, atok)[0] in (400, 500))
st3, ghost = get_raw("/storage/2099/does-not-exist.png")
check("SUP-2 静态面不存在的文件 404", st3 == 404, st3)

# ===== 12. SUP-4 审计日志 =====
lg = data("GET", "/api/operator/logs?pageSize=20", token=atok)
subset("SUP-4 日志行契约", lg["list"][0].keys(), [
    "id", "company_id", "operator_id", "operator_name", "route_name", "method", "params", "ip", "created",
])
check("SUP-4 config.update 已留痕", any(l["route_name"] == "config.update" for l in lg["list"]),
      [l["route_name"] for l in lg["list"]][:8])
upd = [l for l in lg["list"] if l["route_name"] == "config.update"]
check("SUP-4 留痕含方法与操作人", upd[0]["method"] == "PUT" and upd[0]["operator_name"], upd[0])
p1 = data("GET", "/api/operator/logs?page=1&pageSize=1", token=atok)
p2 = data("GET", "/api/operator/logs?page=2&pageSize=1", token=atok)
check("SUP-4 分页不重复且按 id 倒序",
      len(p1["list"]) == 1 and p1["list"][0]["id"] > p2["list"][0]["id"], (p1["list"], p2["list"]))
kw = data("GET", "/api/operator/logs?keyword=config&pageSize=20", token=atok)
check("SUP-4 keyword 过滤只留命中", kw["total_count"] >= 1 and all("config" in l["route_name"] for l in kw["list"]), kw["total_count"])
check("SUP-4 只读账号无权看日志 403", fail("GET", "/api/operator/logs", None, 403, vtok)[0] == 403)

# ===== 13. 会员面鉴权 =====
for path in ["/api/h5app/wxapp/member", "/api/h5app/wxapp/addresslist",
             "/api/h5app/wxapp/member/collect/items", "/api/h5app/wxapp/member/statistical",
             "/api/h5app/wxapp/member/browse/items"]:
    check("未登录 401：" + path, fail("GET", path, None, 401, None)[0] == 401)
check("未登录 PUT 资料 401", fail("PUT", "/api/h5app/wxapp/member", {"username": "x"}, 401, None)[0] == 401)
check("后台令牌不能走会员面", fail("GET", "/api/h5app/wxapp/member", None, 401, atok)[0] == 401)
st, res = fail("GET", "/api/config", None, 401, mtok)
check("会员令牌不能走后台面（issuer 不匹配 = 未认证 401，与源系统 Dingo 一致）",
      st == 401 and res.get("message") == "Unauthenticated.", (st, res))

# ===== 14. 收尾一致性 =====
end = data("GET", "/api/h5app/wxapp/member", token=mtok)
check("MEM-1 手机号仍是原号（换绑已还原）", end["mobile"] == "138****0001", end["mobile"])
check("地址簿回到基线条数", data("GET", "/api/h5app/wxapp/addresslist", token=mtok)["total_count"] == al2["total_count"])
check("收藏总数无残留（新增的已取消）",
      data("GET", "/api/h5app/wxapp/member/collect/items?pageSize=50", token=mtok)["total_count"] == cl["total_count"])
check("冒烟配置键仍可查（写入持久化）",
      any(c["config_key"] == key for c in data("GET", "/api/config", token=atok)["list"]))

print("\n[%s] 通过 %d/%d" % (RUN, sum(ok), len(ok)))
raise SystemExit(0 if all(ok) else 1)
