import json, time, urllib.request, urllib.error

BASE = "http://127.0.0.1:18080"
PW = "admin" + "123"


def req(method, path, body=None, token=None):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(BASE + path, data=data, method=method)
    r.add_header("Content-Type", "application/json")
    if token:
        r.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(r, timeout=10) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode("utf-8"))


ok = []


def check(name, cond, extra=""):
    ok.append(cond)
    print(("PASS " if cond else "FAIL ") + name + ("" if cond else " -> " + str(extra)[:400]))


st, res = req("POST", "/api/operator/login", {"username": "admin", "password": PW})
check("login 200", st == 200, res)
tok = res.get("data", {}).get("token", "")

st, res = req("GET", "/api/goods/items?is_sku=false", token=tok)
check("GOODS-1 list", st == 200 and res["data"]["total_count"] >= 1, res)
print("   total=%s warning_store=%s rows=%s" % (
    res["data"]["total_count"], res["data"]["warning_store"], len(res["data"]["list"])))

st, res = req("GET", "/api/goods/items", token=tok)
anon_st, anon = req("GET", "/api/goods/items")
check("GOODS-1 anon 401", anon_st == 401, anon)

# 复跑清理：固定 item_bn 的上一次实跑残留会撞唯一键，先删干净再建
st, res = req("GET", "/api/goods/items?item_bn=RUN-TAB-T", token=tok)
for r in res["data"]["list"]:
    req("DELETE", "/api/goods/items/%d" % r["item_id"], token=tok)

body = {
    "item_name": "实跑平板T", "item_bn": "RUN-TAB-T", "price": 88800, "store": 12,
    "approve_status": "onsale", "category": [2], "pics": ["/r/1.jpg"],
    "barcodes": ["6901056999100"], "tag_ids": [1],
    "attrs": [{"attribute_id": 3, "attribute_value_id": 5}],
    "type_labels": [{"label_id": 2, "label_name": "7天无理由", "label_price": 0, "num_type": "plus", "num": 1}],
    "spec_items": [
        {"item_name": "实跑平板T 白 64G", "item_bn": "RUN-W64", "price": 77700, "store": 5,
         "item_spec": [{"attribute_id": 1, "attribute_value_id": 1}, {"attribute_id": 2, "attribute_value_id": 3}]},
    ],
}
st, res = req("POST", "/api/goods/items", body, tok)
check("GOODS-3 create", st == 200 and res["data"]["item_id"] > 0, res)
new_id = res["data"]["item_id"]

st, res = req("GET", "/api/goods/items/%d" % new_id, token=tok)
d = res["data"]
check("GOODS-2 admin detail", st == 200 and d["goods_id"] == d["item_id"] == d["default_item_id"], res)
check("GOODS-2 spec_items=1", len(d["spec_items"]) == 1, d["spec_items"])

st, res = req("GET", "/api/h5app/wxapp/goods/items/%d" % new_id)
d = res["data"]
check("GOODS-8 front detail anon", st == 200, res)
check("GOODS-8 cur/tdk", d["cur"]["currency"] == "CNY" and "演示商城" in d["tdk_data"]["title"], d.get("cur"))
check("GOODS-8 item_spec_list 2 轴", len(d["item_spec_list"]) == 2, d["item_spec_list"])
check("GOODS-8 custom_spec_name", d["spec_items"][0]["custom_spec_name"] == "白色、128G", d["spec_items"][0])
check("GOODS-8 params 产地", d["item_params_list"][0]["attribute_value"] == "中国大陆", d["item_params_list"])
check("GOODS-8 tagList", len(d["tagList"]) == 1, d["tagList"])

st, res = req("DELETE", "/api/goods/items/%d" % new_id, token=tok)
check("GOODS-5 delete", st == 200 and res["data"]["result"] is True, res)
st, res = req("GET", "/api/h5app/wxapp/goods/items/%d" % new_id)
check("GOODS-5 front 404 after delete", st == 404, res)

print("\n%d/%d passed" % (sum(ok), len(ok)))
raise SystemExit(0 if all(ok) else 1)
