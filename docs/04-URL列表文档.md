# kotlin-ecshopx URL 列表文档

> 版本：v1.0 · 数据来源：`thirdparty/ECShopX/routes/**` 机械提取（2199 端点，见[全量附录](./附录-ECShopX全量路由清单.md)）+ 核心 Action 源码精读
> 本文档定义 **Kotlin 版接口契约**。P0 端点给出逐字段入参/出参；P1/P2 端点给出摘要，进入开发期再补逐字段规格。

## 1. 全局约定

| 项 | 约定 |
|---|---|
| 基址 | 消费者端 `/api/h5app/…`、后台 `/api/…`、员工端 `/api/admin/wxapp/…`、超管 `/api/superadmin/…`（与 PHP 完全一致） |
| 成功包络 | `{"data": …}`；列表 `{"data":{"total_count":123,"list":[…]}}` |
| 错误包络 | `{"message":"…","errors":{"field":["msg"]},"code":400,"status_code":400}`；HTTP 状态按语义（`ecx.compat.force-ok-status=true` 时回退 PHP 的 200 行为） |
| 鉴权 | `Authorization: Bearer <JWT>`；员工端另有 `x-wxapp-session` 头 |
| 分页入参 | `page`(≥1) + `pageSize`（兼容 `page_size`；默认 10，上限 100 截断） |
| 金额 | 全部整数字符串或数字，**单位=分**；字段名沿用 `price/total_fee/item_fee/freight_fee/discount_fee…` |
| 时间 | Unix 秒整数（`created/updated/begin_time…`） |
| 命名 | 出入参 snake_case（源系统个别 camel 字段保留原样，逐条标注） |
| company_id | 一律由服务端从令牌解析，**客户端传入即忽略**（后台切店铺用 `distributor_id` 参数） |

## 2. P0 端点总表（首发 ≈120 条）

编号规则：`{域}-{序号}`，用于[开发步骤](./05-开发步骤-M0M1M2-P0P1P2.md)排期与附录对照。

### 2.1 认证 AUTH（5）

| # | 方法 | URL | 说明 | 鉴权 | PHP 原型 |
|---|---|---|---|---|---|
| AUTH-1 | POST | `/api/operator/login` | 后台登录 | 公开 | `AuthController@login` |
| AUTH-2 | GET | `/api/token/refresh` | 刷新令牌 | jwt | closure(jwt.refresh) |
| AUTH-3 | GET | `/api/token/invalidate` | 登出 | jwt | closure |
| AUTH-4 | POST | `/api/h5app/wxapp/new_login` | 消费者登录 | 公开 | `LoginController@login` |
| AUTH-5 | GET | `/api/h5app/wxapp/token/refresh` | 消费者刷新 | 会员 | closure |

**AUTH-1 入参**

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| username | string | Y | 运营账号 |
| password | string | Y | BCrypt 校验 |
| logintype | string | N | 默认 `localadmin`；Kotlin M0 仅实现 localadmin/staff/distributor，其余 OAuth 型 P2 |
| token, yzm | string | 条件 | 开启图形验证码时必填（`ADMIN_LOGIN_CHECK_LEVEL` 对应配置 `ecx.security.login-captcha`） |

**AUTH-1 出参**：`{"data":{"token":"<JWT>"}}`；claims：`sub=operatorId, kind=OPERATOR, company_id`。
失败：401 `{"message":"用户名或密码错误","code":401,"status_code":401}`。

**AUTH-4 入参**（按 `auth_type` 分支，源系统行为）

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| auth_type | string | Y | M0 实现 `local`（手机号）+ `wxapp`（mock 通道）；`wx_offiaccount/aliapp/social_oauth` P2 |
| username | string | local 时 Y | 手机号 |
| check_type | string | local 时 Y | `mobile`(短信码)/`password` |
| vcode / password | string | 条件 | 验证码或密码 |
| auto_register | 0/1 | N | 缺号是否注册，默认 1 |
| silent | 0/1 | N | 1=错误进 body 不抛 HTTP 错 |
| appid/code/iv/encryptedData/rawData/signature | string | wxapp 时 | 小程序 jscode 链路（M0 走 Mock，P1 真实） |
| salesperson_id / trustlogin_tag / version_tag / cloudID | mixed | N | 归因/信任登录，透传存储 |

**AUTH-4 出参**：`{"data":{"token":string|null,"pre_login_data":{open_id,unionid,mobile…},"status":1|0,"error_message":string|null,"is_new":0|1}}`

### 2.2 商品 GOODS（8）

| # | 方法 | URL | 说明 | 鉴权 |
|---|---|---|---|---|
| GOODS-1 | GET | `/api/goods/items` | 后台商品列表 | jwt |
| GOODS-2 | GET | `/api/goods/items/{item_id}` | 后台商品详情 | jwt |
| GOODS-3 | POST | `/api/goods/items` | 新增商品 | jwt |
| GOODS-4 | PUT | `/api/goods/items/{item_id}` | 更新商品 | jwt |
| GOODS-5 | DELETE | `/api/goods/items/{item_id}` | 删除商品（逻辑下架） | jwt |
| GOODS-6 | GET | `/api/goods/sku` | SKU 列表 | jwt |
| GOODS-7 | GET | `/api/h5app/wxapp/goods/items` | 前台商品列表（公开） | 公开 |
| GOODS-8 | GET | `/api/h5app/wxapp/goods/items/{item_id}` | 前台详情（公开） | 公开 |

**GOODS-1 入参**（query，除分页外全部可选——与 PHP `getItemsList` 对齐）
`page,pageSize`(Y)；筛选：`item_name,keywords,item_bn,barcode,item_id,goods_id,category(叶子→展开),main_cat_id,approve_status(onsale|instock|processing|rejected),audit_status,distributor_id(数字|all_distributor|csv),supplier_id,item_holder(platform|distributor|supplier),item_source,operate_source,type(0|1),item_type(默认 services 排除),is_sku('true' 返回全部SKU行),nospec,is_gift('true'|'false'),is_warning,brand_id,tag_id,rebate(0-3),templates_id,special_type(normal|drug),created_time_start,created_time_end`
注：源系统 `price_gt/price_lt/store_gt/store_lt` 以**元**入参内部 ×100——Kotlin 版保持同行为（URL 契约兼容），字段文档标注陷阱。

**GOODS-1 出参** `data`：

| 字段 | 类型 | 说明 |
|---|---|---|
| total_count | int | 总数 |
| warning_store | int | 全局预警库存阈值 |
| list[] | 商品行 | 主行=默认 SKU |

商品行字段（GOODS-1/6 同构）：`item_id,item_type,item_category,consume_type,item_name,item_bn,barcode,brief,company_id,price,cost_price,market_price,item_unit,store,sales,nospec,is_default,default_item_id,goods_id,pics[],videos,weight,volume,approve_status,audit_status,distributor_id,supplier_id,brand_id,goods_brand,type,is_point,point,rebate,is_gift,is_medicine,is_prescription,created,updated,item_cat_id,item_main_cat_id,spec_items[]`（`is_sku=true` 时每行含 `item_spec[{attribute_id,attribute_value}]`；多规格主行聚合 `spec_items` 子行）与 `type_labels[]`。

**GOODS-3 入参**（form/json，节选必填）：`item_name`(Y)、`goods_id`/`default_item_id`、`price`(Y,分)、`store`(Y)、`category[]`、`templates_id`、`pics[]`、`intro`、`attrs[]{attribute_id,attribute_value_id|custom_attribute_value}`、`spec_items[]{item_name,item_bn,price,store,item_spec[]}`。出参：`{"data":{"item_id":N}}`。校验失败 400+errors。

**GOODS-8 前台详情出参**（`data`，对齐 V2 Action）：商品行全部字段 + `intro(富文本)`、`spec_items[]`(含 `custom_spec_id:"1-2"`,`custom_spec_name:"红、L"`)、`item_spec_list[]`、`item_params_list[]`、`member_price`、`distributor_info{}`、`cur{currency,symbol,rate}`、`tdk_data{title,mate_description,mate_keywords}`、`recommend_items[]`、`tagList[]`。

### 2.3 购物车 CART（6）

| # | 方法 | URL | 说明 |
|---|---|---|---|
| CART-1 | POST | `/api/h5app/wxapp/cart` | 加购（单件/批量） |
| CART-2 | GET | `/api/h5app/wxapp/cart` | 购物车列表 |
| CART-3 | DELETE | `/api/h5app/wxapp/cartdel` | 删除单行 |
| CART-4 | PUT | `/api/h5app/wxapp/cartupdate/num` | 改数量 |
| CART-5 | PUT | `/api/h5app/wxapp/cartupdate/checkstatus` | 勾选/取消 |
| CART-6 | GET | `/api/h5app/wxapp/cartcount` | 角标数量 |

**CART-1 入参**：`item_id`+`num`（或批量 `items[]{item_id,num}`）；`shop_id`/`distributor_id`、`shop_type(shop|distributor|community)`、`activity_type(normal|package)`+`activity_id`、`cart_type(cart|fastbuy|offline)`、`isAccumulate`(bool，数量累加或覆盖)、`promoter_user_id`、`iscrossborder`。
出参 = CART-2 全量购物车结构。

**CART-2 出参** `data`：

```
valid_cart[]   // 按店铺分组
  ├ shop_id, shop_name, address, mobile, lat, lng, hour, is_ziti, is_delivery,
  ├ cart_total_price, item_fee, freight?, discount_fee, total_fee, cart_total_num, cart_total_count,
  ├ member_discount, used_activity[], used_activity_ids[], gift_activity[], plus_buy[]
  └ list[]     // 购物车行
      cart_id, company_id, user_id, shop_type, shop_id, activity_type, activity_id,
      item_type, item_id, items_id[], item_name, pics, price, num, is_checked, is_plus_buy,
      is_last_price, discount_fee, total_fee, store, market_price, brief, approve_status,
      item_spec_desc, parent_id, goods_id, item_category, type, grade_name, member_discount,
      activity_info[]{id,type,info,rule,discount_fee}
invalid_cart[] // 同构，失效行
is_check_store // 是否做库存校验提示
```

### 2.4 订单 ORDER（9）

| # | 方法 | URL | 说明 |
|---|---|---|---|
| ORDER-1 | POST | `/api/h5app/wxapp/getFreightFee` | 结算预览（算价） |
| ORDER-2 | POST | `/api/h5app/wxapp/order_new` | 创建订单（不含支付） |
| ORDER-3 | POST | `/api/h5app/wxapp/order` | 创建订单+预支付（返回 JSAPI 参数） |
| ORDER-4 | GET | `/api/h5app/wxapp/orders` | 我的订单列表 |
| ORDER-5 | GET | `/api/h5app/wxapp/order/{order_id}` | 订单详情 |
| ORDER-6 | POST | `/api/h5app/wxapp/order/cancel` | 用户取消 |
| ORDER-7 | POST | `/api/h5app/wxapp/order/confirmReceipt` | 确认收货 |
| ORDER-8 | GET | `/api/h5app/wxapp/orderscount` | 各状态角标数 |
| ORDER-9 | GET | `/api/orders` + GET `/api/orders/{order_id}` 等 | 后台列表/详情/发货 PUT `/api/orders/delivery`（后台族，摘要） |

**ORDER-1/2/3 公共入参**（form）：

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| cart_type | string | Y | `cart`/`fastbuy`/`offline` |
| order_type | string | Y | M0：`normal`；`service`(次卡)/`groups`/`seckill` P1/P2 |
| item_id+item_num 或 items[]{item_id,num} | | fastbuy 时 Y | cart 模式忽略（读车内勾选项） |
| shop_id / distributor_id | int | N | 店铺维度 |
| receipt_type | string | N | `logistics`(默认)/`ziti` |
| receiver_name,receiver_mobile,receiver_state,receiver_city,receiver_district,receiver_address | string | 物流单必填 | 或传 `address_id` 服务端展开（新增便捷参数，出参同 PHP） |
| coupon_discount | string | N | 券码 |
| not_use_coupon / point_use / pack | bool | N | 不用券/用积分/打包 |
| remark, invoice_content, invoice_type | string | N | |
| salesman_id, promoter_user_id, source_id, monitor_id | | N | 归因链 |
| pay_type | string | ORDER-3 必填 | M0：`point`/`deposit`/`offline_pay`/`wxpay(mock)`；`alipaymini/adapay/paypal…` P2 |
| return_url | string | N | |

**ORDER-1 出参**（preview，`data`，与 PHP `getOrderFreightFeeInfo` 同构——金额均为分）：
`item_fee, freight_fee, discount_fee, discount_info[]{type,rule,discount_fee}, coupon_discount, coupon_info[]{id,coupon_code,info,type,rule,discount_fee}, member_discount, goods_discount, point, point_use, point_fee, get_points, bonus_points, user_point, max_point, limit_point, total_fee, total_rebate, totalItemNum, cost_fee, fee_type,fee_rate,fee_symbol, auto_cancel_time, items[]{item_id,item_bn,item_name,pic,num,price,item_fee,total_fee,…}, distributor_id, pack, is_logistics`

**ORDER-2 出参**：`{"data":{"order_id":"70…","pay_type":…,"order_type":…,"team_id":null,"total_fee":N}}`。
**ORDER-3 出参**：`{"data":{"appId","timeStamp","nonceStr","package":"prepay_id=…","signType","paySign","trade_info":{"order_id","trade_id"},"order_created"}}`（M0 mock 渠道返回同形假参数）。

**ORDER-4 入参**：`page,pageSize(Y)`、`status`(int：1待收货 3完成 4待自提 5待付款 6待发货 7待评价)、`order_status/pay_status/delivery_status`(string 可选精确态)、`order_id,mobile,time_start_begin,time_start_end,distributor_id,is_distribution,from`。user 取自令牌。
**ORDER-4 出参**：`{"data":{"list":[orderRow],"total_count":N}}`，orderRow=`orders_normal_orders` 主要列 + `items[]` 摘要 + `distributor{name,logo}`。

**ORDER-5 出参**（`data`）：`orderInfo{}`（订单全列，命名对齐 PHP：`order_id,title,user_id,item_fee,freight_fee,total_fee,pay_fee?,order_status,pay_status,delivery_status,cancel_status,receipt_type,ziti_code,ziti_status,receiver_*,member_discount,coupon_discount,discount_fee,discount_info,point,point_use,point_fee,get_points,pay_type,remark,invoice,fee_type/fee_rate/fee_symbol,auto_cancel_time,auto_finish_time,end_time,create_time,update_time,type,order_class,order_source,salesman_id,distributor_id,shop_id,delivery_corp/code/img/status/time,is_rate`）`+ items[]{id,order_id,item_id,item_bn,item_name,pic,num,price,total_fee,item_fee,cost_fee,templates_id,rebate,item_unit,member_discount,coupon_discount,discount_fee,delivery_status,aftersales_status,refunded_fee,item_spec_desc,order_item_type(normal|gift|plus_buy),share_points,point_fee,get_points} + order_status_des, order_status_msg, distributor{}, tradeInfo{}, cancelData{}, logistics_items[]`。

### 2.5 支付 PAY（5）

| # | 方法 | URL | 说明 |
|---|---|---|---|
| PAY-1 | GET | `/api/h5app/wxapp/payment/config` | 取既有订单支付参数 |
| PAY-2 | POST | `/api/h5app/wxapp/payment` | 支付订单（point/deposit 即时结清） |
| PAY-3 | POST | `/api/h5app/wxapp/payment/query` | 轮询支付结果 |
| PAY-4 | POST | `/api/h5app/wxapp/payment_deposit` | 储值充值支付（CZ 单） |
| PAY-5 | POST | `/api/notify/wxpay`（webhook 组） | 渠道异步回调（XML/form，非 data 包络） |

**PAY-2 入参**：`order_id`(Y，`CZ*`→充值单)、`pay_type`(Y)、`order_type`、`member_card_code/coupon_code`(deposit 活动)、`return_url`。
**出参**：同 ORDER-3 的渠道参数包 + `order_info{}`（=ORDER-5 orderInfo，items 拆 `items/logistics_items`）。point/deposit 结清时 `order_info.pay_status=PAYED` 即成功。
**PAY-3 入参**：`trade_id`(Y)；**出参**：`{"data":{"trade_state":"SUCCESS","order_id":…,"pay_fee":…}}`。
**PAY-5**：验签→条件更新（§详细设计 3.2）→渠道应答体；重复通知幂等返回成功。

### 2.6 会员 MEMBER（8）

| # | 方法 | URL | 说明 | 关键入参/出参 |
|---|---|---|---|---|
| MEM-1 | GET | `/api/h5app/wxapp/member` | 我的信息(脱敏) | 出参：user_id,grade_id,mobile(掩码),nickname,avatar,sex,birthday,gradeInfo{},nextGradeInfo{},totalConsumption,point,deposit,is_new… |
| MEM-2 | PUT | `/api/h5app/wxapp/member` | 修改资料 | username,avatar,sex,birthday,address,email,industry,income,edu_background,habbit[]{name,ischecked}；`mobile/company_id` 拒改；掩码值忽略 |
| MEM-3 | PUT | `/api/h5app/wxapp/member/mobile` | 换绑手机 | mobile+vcode(Y) |
| MEM-4 | GET/POST/DELETE | `/api/h5app/wxapp/addresslist`、POST 新增、PUT `/{address_id}`、DELETE | 地址 CRUD | username,telephone,province,city,county,adrdetail,postalCode,is_def；出参 members_address 行 |
| MEM-5 | GET | `/api/h5app/wxapp/member/collect/items` | 收藏列表(分页) | 出参 list[]{fav_id,item_id,item_name,item_image,item_price,…} |
| MEM-6 | POST/DELETE | `/api/h5app/wxapp/member/collect/items/{item_id}` | 收藏/取消 | — |
| MEM-7 | GET | `/api/h5app/wxapp/member/statistical` | 订单角标+资产 | point,deposit,orderscount{} |
| MEM-8 | POST | `/api/h5app/vcode` | 短信验证码 | mobile,type；M0 mock 恒码(配置)，P1 阿里短信 |

### 2.7 店铺/其他（P0 支撑 6）

| # | 方法 | URL | 说明 |
|---|---|---|---|
| SUP-1 | GET/PUT | `/api/config`(后台族) | sys_config 读写 |
| SUP-2 | POST | `/api/upload/image` | 图片上传→upload_files→`/storage/**` 静态 |
| SUP-3 | GET | `/api/distributor/list` 简版 | 店铺下拉（P1 完整） |
| SUP-4 | GET | `/api/operator/logs` 分页 | 审计日志（shoplog 数据） |
| SUP-5 | GET | `/actuator/health` | 运维 |
| SUP-6 | POST | `/api/h5app/wxapp/order/offline_pay_confirm`* | 线下转账凭证提交（对应 offline_payment 审核流；*新增语义端点，PHP 由订单详情触发） |

## 3. P1/P2 端点摘要（增量规格进入开发时补全）

| 域 | P1（M1 交付） | P2（M2 及以后） |
|---|---|---|
| 售后 | aftersales 申请/列表/详情/审核/退货物流/退款查询（前后端 ≈20 条，原型见附录 `routes/api/aftersales.php`、`routes/frontapi`） | 换货、上门取退 |
| 营销 | 满减/折扣活动 CRUD+前台位、优惠券 CRUD+领取+核销、拼团(活动/开团/参团/成团任务)、秒杀 | 砍价、签到/任务/抽奖、人群折扣、加价购扩展 |
| 店铺分销 | distributor CRUD、店铺商品上下架/价格库存、导购 CRUD+分润单、提现申请/审核、自提点、地理围栏查询 | 分账(渠道侧)、快证/旺店通店铺映射 |
| 积分储值 | 积分规则入账/抵扣上限、余额充值/流水、充值赠品规则 | 积分商城、互动升值 |
| 评价 | trade_rate 提交/列表/商家回复 | 晒单审核、精选 |
| 发票 | 申请单 CRUD（简化 1 表） | 开票渠道对接（诺诺/票通） |
| 结算 | — | statements 账期任务+对账单 API |
| 对接 | — | openapi 签名网关骨架、systemlink ERP、微信开放平台事件、模板消息、小程序码 |

统计口径：P0=本文 §2 共 ≈47 条主干 + 后台订单/商品族细分 ≈120 条；P1 预计再交付 ≈450 条；P2 承接其余 ≈1600 条（多为对接/长尾，逐条对照见附录）。

## 4. 与 PHP 契约的差异清单（显式破坏性变更）

| # | 变更 | 缓解 |
|---|---|---|
| D1 | 错误不再一律 HTTP 200（401 除外规则废除） | 兼容开关 `force-ok-status` |
| D2 | `logintype` OAuth 分支 M0 未实现，返回 501+BIZ | 按租户配置隐藏入口 |
| D3 | 价格参数禁止客户端传入（PHP createOrder 允许部分透传的字段忽略） | 前端本就以预览为准 |
| D4 | `price_gt/price_lt` 元/分陷阱保留但不扩散：新增字段一律分 | 文档标注 |
| D5 | 次卡/服务单（`order_type=service`）与 rights 系列 M1 才回 | 小程序侧功能开关 |
| D6 | `/api/orders/{order_id}` 后台详情出参剔除药品/跨境扩展列 | P2 回补 |

## 5. 实现期语义补充（M0-4 商品域落地时确认，追加于 §4 之后，不回改上文）

| # | 条目 | 实现口径 |
|---|---|---|
| C1 | `store_gt/store_lt` | 库存边界按原值比较，**不做 ×100**；与 D4 里 `price_gt/price_lt`（元→分）规则不同，前端容易踩，端到端测试用 `price_lt=100`/`store_gt=150` 各只命中一行来钉死这一差异 |
| C2 | `price_gt/price_lt` | 入参单位=元，服务端换算成分后与 `items.price`（分）比较 |
| C3 | 出参金额 | 一律分，不换算（`price/cost_price/market_price/label_price`） |
| C4 | `is_warning` | `true → store <= warning_store`，`false → store > warning_store`，两分支互斥（行内 `warning_store` 是商品自身阈值） |
| C5 | 列表外层 `warning_store` | 取 `sys_config` 的 `item.warning_store`（租户全局阈值），与 C4 的行内字段同名不同义 |
| C6 | `is_sku=true` | 主行与子行同构输出，主行同样带 `item_spec`（主行自身不挂规格时为空数组） |
| C7 | `spec_items` | 恒为数组（无规格=空数组）；出参字段集合不随"主行/子行/详情"变化，便于契约 diff |
| C8 | `category` | 支持单值与 csv，服务端按分类树展开到全部子孙；`item_cat_id`=首个挂载分类，`item_main_cat_id`=沿 parent 链上溯到的 `is_main_category` 祖先（叶子分类挂载时也能出主分类） |
| C9 | `barcode` 过滤 | 条码可挂在子行，默认列表只看主行，故命中子行需配合 `is_sku=true` |
| C10 | GOODS-3 主行自指 | 自增 id 只有 INSERT 后可知，主行落库后补 `goods_id = default_item_id = item_id`；子行继承主行的 `goods_id/default_item_id` |
| C11 | GOODS-4 `spec_items` | 带 `item_id` 视为改子行，缺省视为新增子行；未列出的子行不删除（与 PHP 一致的宽松语义） |
| C12 | GOODS-5 删除 | 逻辑下架：主行 + 全部子行 `approve_status=offline_sale`，前台详情 404、前台列表不出现，后台详情仍可读 |
| C13 | GOODS-8 回填占位 | `member_price` 暂按售价回填（会员等级折扣属 M0-8）；`distributor_info`（M1）、`goods_brand`（M1 品牌主数据）先出空值占位保字段 |
| C14 | 入参命名 | 商品写接口用 snake_case，通过 DTO 上的 `@JsonNaming(SnakeCaseStrategy)` 局部生效，不开全局命名策略（避免扰动 M0-3 已定契约） |
| C15 | null 字段 | Jackson `default-property-inclusion=always`：源系统 Fractal 会输出值为 null 的键（如 `barcode/brief/weight`），本项目同样输出以保证字段集合可比对 |
| C16 | `type_labels` | 来源 `items_rel_type`（服务标签快照，含 `label_name/label_price/num_type/num/is_not_limit_num/limit_time`），非实时 join `servicelabels` |

## 6. 实现期语义补充（M0-5 购物车与算价落地时确认，追加于 §5 之后，不回改上文）

| # | 条目 | 实现口径 |
|---|---|---|
| C17 | 单条算价链路 | CART-2 列表、ORDER-1 预览、M0-6 下单**都调用同一个 `CartService.quote`**（同一 `PriceContext` + 同一条 `PriceRule` 责任链，纯函数、不查库、不取时间、无随机），"预览价==下单价"是结构事实而非约定；属性测试以"同用例建两份互不共享对象的 ctx，逐分比对全量快照"钉死（1000 例随机购物车×券×等级） |
| C18 | 有效/失效判定 | `valid = 商品存在 且 approve_status='onsale' 且 store>0`；其余进 `invalid_cart`（按店铺分组同构输出）。ORDER-1 的 `invalid_items[].reason` 三档：商品不存在 / 库存不足 / 商品已下架 |
| C19 | 未勾选行 | 仍出现在组内 `list`（字段齐全、带 `is_checked=false` 与行小计），但 `item_fee / cart_total_price / cart_total_num / cart_total_count / discount_fee / freight / total_fee` 一律只统计勾选行 |
| C20 | `is_last_price` | = 车内快照价 ≠ 商品现价。**算价永远用现价**，快照只用于提示"价格已变动"，加购命中已有行时刷新快照（名称/图片/价格/件数） |
| C21 | 加购唯一键 | `(company_id, user_id, item_id, shop_id, activity_type, activity_id, is_plus_buy)`；命中则改行数、未命中插入。`isAccumulate=true` 累加到原数量，缺省（false/不传）**覆盖**数量 —— 与 PHP `CartServices` 一致，批量加购里出现已有 SKU 时是覆盖不是累加 |
| C22 | 会员折扣 | `members_grade.discount` 是**百分比**（100=无折扣）；折让逐行 `floor(行小计 × (100-discount) / 100)`，落在行 `member_discount` 与 `discount_fee`，组/预览的 `member_discount` 是勾选行之和。`grade_name` 随行输出（无等级=普通会员） |
| C23 | 店铺分组 | 按 `shop_id` 分组并保持首次出现顺序；`shop_id` 取 `shop_id`/`distributor_id` 中**非 0 的那个**，商品加购缺省用 `items.distributor_id`。`shop_id=0` 视为平台自营，`shop_name` 回落 `company.company_name`；其余档案列（`shop_name/address/mobile/lat/lng/is_ziti/is_delivery`）取自 `distribution_distributor`，`hour` 该表无对应列 → 空串占位 |
| C24 | 运费 | 组内取**首个 `templates_id>0`** 的模板（一店一模板口径）：`is_free='y'` → 0；组内商品应付 ≥ `minprice`（**元→分**）→ 0；否则 `fee_conf.first_fee`（元→分）× 计价量，`valuation` 1=件数 2=重量 3=体积（重量/体积按行 `×num` 求和后向上取整，且至少 1 档）；无模板或模板缺失 → 0（演示环境默认包邮）。地区分级与续重属 P1 |
| C25 | 券与积分 | M0 为**开关位**：`coupon_code`/`not_use_coupon`/`point_use` 接受但不产生金额（`coupon_discount=0`、`coupon_info=[]`、`point_fee=0`），字段与结构先就位，券核销属 M1-2、积分抵扣属 M0-8。`point` 回填"商品积分×件数"供前端展示可得积分 |
| C26 | CART-2 fastbuy | `GET /cart?cart_type=fastbuy&items= item_id:num,...` 用临时行（`cart_id=0`，不落库），出参结构与车内完全同构 |
| C27 | 写端点出参 | CART-1/3/4/5 成功后一律回吐 **CART-2 全量购物车**（前端一次刷新，无需二次 GET），与 PHP 一致 |
| C28 | CART-6 角标 | `count`=车内行数、`num`=件数之和，**不区分勾选**（PHP `getCount` 语义） |
| C29 | ORDER-1 形态 | 跨店铺**扁平聚合**（`item_fee/freight_fee/discount_fee/total_fee` 为各组合计）另给 `group_count`；`items[]` 为参与算价的勾选行（含 `cart_id`，M0-6 下单据此清车），失效行在 `invalid_items[]` |
| C30 | 加价购行 | `is_plus_buy` 行参与算价（计入 `item_fee`），同时单列进组内 `plus_buy[]`；因此它必须是唯一键的一部分，否则与普通行互相覆盖 |
| C31 | 金额单位 | 与 §5/C3 同：出参一律分；只有 `shipping_templates.minprice`/`fee_conf.first_fee`（DDL 为元）在读入时换算成分 |

## 7. 实现期语义补充（M0-6 订单创建与状态机落地时确认，追加于 §6 之后，不回改上文）

| # | 条目 | 实现口径 |
|---|---|---|
| C32 | 下单取价 | ORDER-2 复用 C17 的**同一条 `CartService.quote`**（fastbuy 走 `quoteFromEntries`、车内走 `quoteFromCart`），客户端传来的 `price/total_fee/item_fee` 一律忽略；端到端用 `{total_fee:1, price:1}` 伪入参钉死"仍按 8900 收" |
| C33 | 一单一店 | `group_count > 1` → 422「暂不支持多店铺合并下单」；`orders_normal_orders.original_order_id` 列已建但 M0 不写值，拆单（PHP `split_order`）属后续里程碑 |
| C34 | `order_id` 类型 | 16 位（`yyMMddHHmmss` + 段内 4 位），**超出 JS 安全整数，出参恒为字符串**；入参路径变量/表单同样按字符串解析，`order_id=not-a-number` → 404 而非 500 |
| C35 | `client_token` 幂等 | 键 `companyId:userId:client_token`，同令牌重复提交返回**同一 `order_id`** 且不重复扣库存。M0 用 Caffeine（30m TTL / 20k，单 JVM），多实例部署前须换 Redis SETNX（M1） |
| C36 | 库存并发 | 扣减 `UPDATE items SET store=store-?, sales=sales+? WHERE store>=?`、回补 `store=store+?, sales=CASE WHEN sales>=? THEN sales-? ELSE 0 END` 全 CAS；影响 0 行 → 事务回滚 → 422，**不存在先读后写**（门槛：50 线程抢 30 件恰好成 30 单，`store=0/sales=30`，无孤儿明细行） |
| C37 | 状态机 | `(order_status, event) → Transit` 白名单驱动（`OrderStatusMachine`，15 条箭头，单测钉死全集合）；非法迁移 422 + `errors.order_status`；写库用 `transit(... WHERE order_status=? AND pay_status=?)` 乐观 CAS，0 行 → 422「订单状态已变更，请刷新后重试」 |
| C38 | 分包补发 | `WAIT_BUYER_CONFIRM + SHIP` 是**自环**（状态不回退）：整单已在待收货但仍有明细未发时允许再发一包。"发齐"由 `shipPlan` 判定（无未发行 → 422「订单已发货完毕」），`CANCEL + SHIP` 仍非法 |
| C39 | 行级发货态 | `delivery_status`（`PART_DONE`/`DONE`）按**明细行**判定：`delivery_item_num + 本次件数 >= num` 才置 `DONE`；单头 `delivery_status` 与 `orders_associations` 由整单是否发齐收口，多包裹时 `logistics_items[].package_type` 首包 `sep`、补包 `batch` |
| C40 | 确认收货 | 只做状态迁移 + 留痕，**不回补库存、不写 `cancel_item_num`、不生成取消申请单** —— 这些是关单（CANCEL/TIMEOUT_CANCEL）专属副作用；曾误把 confirm 路由到关单方法导致"收货后库存凭空 +N" |
| C41 | 超时关单 | `auto_cancel_time = create_time + order.auto_cancel_minutes×60`；`@Scheduled` 扫到期单逐条 CAS 关闭（回补库存 + `cancel_item_num=num-delivery_item_num` + `cancel_from=system` 取消单 + `timeout_cancel` 留痕），成功后 `auto_cancel_time` 置 NULL 即"清定时器"，重扫幂等 0 条。未引入 ShedLock，靠 CAS 保证多实例安全 |
| C42 | ORDER-4 页签 | 数字 `status` → 1 待收货 / 3 交易成功 / 4 待自提 / 5 待付款 / 6 待发货 / 7 待评价；显式 `order_status` 字符串参数**优先于** `status`（两者同时给时以后者为准） |
| C43 | ORDER-8 角标 | 单条 `SUM(CASE…)` 出 7 键（`notpay/pending_delivery/pending_receive/done/evaluation/cancel/total`），分桶互斥且求和恒等 `total`（端到端与实跑都断言该不变量）；`evaluation` = `DONE 且 is_rate=false`，M0 不落评价故 `is_rate` 恒 false，评价域属 M1 |
| C44 | 自动完成时刻 | 发货时写 `auto_finish_time = delivery_time + order.auto_finish_days×86400`（seed=10 天），与 `auto_cancel_time` 复用 `end_time` 列语义：有未完成定时器才非空 |
| C45 | ORDER-5 出参 | 前台**不输出**流转日志（`operate_logs=[]`）；`tradeInfo={}`、`coupon_info=[]`、`cancelData` 仅取消过才有值；`distributor` 取店铺档案三源合并，`receiver_*` 由 `address_id` 展开地址簿快照后落库（后续改地址不影响历史单） |
| C46 | 后台 ORDER-9 族 | `/api/orders`（列表，权限码 `orders.lists`）、`/api/orders/{order_id}`、`PUT /api/orders/remark`、`PUT /api/orders/delivery`（`orders.update`/`orders.delivery`）、`/api/orders/timeout`（到期单排查，只回 `order_ids`）；`user_id` 等筛选参数走后台链，前台会员令牌访问后台链一律 401 |
| C47 | ORDER-3 / 支付 | ORDER-3（订单支付/`pay_trade`）与 `pay_fee` 回填属 M0-7；M0-6 先落 `markPaid(orderId, payFee, payType, payChannel)` 挂点：PAY 箭头迁移 + 幂等（已付款再调返回 false），`pay_status=PAYED` 后 `pay_fee=total_fee` |

## 8. 实现期语义补充（M0-7 支付闭环落地时确认，追加于 §7 之后，不回改上文）

| # | 条目 | 实现口径 |
|---|---|---|
| C48 | `trade` 生命周期 | 只有 `NOTPAY → SUCCESS`（结清）与 `NOTPAY → CLOSED`（作废）两条出边，全部是 `UPDATE … WHERE trade_state='NOTPAY'` 条件更新；影响 0 行即代表并发已抢到，调用方**不得**再做动账与订单迁移 |
| C49 | 应付/实付分账 | `trade.total_fee` **只取订单表**（`orders_normal_orders.total_fee`），渠道报文里的金额只做**校验**不参与计算；结清时 `pay_fee` 同时写 `trade.pay_fee`（渠道真相）与 `orders_normal_orders.pay_fee`（订单实付，M0-7 新增列，默认 0） |
| C50 | 换渠道重试 | 同一订单**至多一条活跃支付单**：`pay_type` 不变复用原单（改金额需重新下单），`pay_type` 变化则旧单 `CLOSED` + 新发一单；因此"重试"永不产生 NOTPAY 堆积（端到端断言 `COUNT(NOTPAY)=0`、`COUNT(SUCCESS)=1`） |
| C51 | PAY-2 出参 | `data` = 渠道参数包 + 支付摘要（`order_id/trade_id/trade_state/pay_type/total_fee/pay_fee/trade_info`）+ **ORDER-5 detail 合并**（`orderInfo/items/logistics_items`），前端一次拿到"付完了 + 订单长什么样"；point/deposit 即时结清时 `orderInfo.pay_status=PAYED` |
| C52 | 积分支付 | M0 口径是**纯积分全额兑换**（1 积分 = 1 分，`PointRate.POINT_PER_FEN=1`），`point < total_fee` → 422「积分不足」；混合支付（`point.deduct.max_ratio` 按比例抵扣）留 M1，扣减仍是 `WHERE point >= ?` 条件更新 |
| C53 | 余额支付 | **赠金优先**（先扣 `gift_amount` 再扣 `deposit_amount`），整行乐观比对 `user_deposit.swap`；余额不足 422「余额不足，当前 N 分」+ `errors.deposit`，**不动账、不改订单状态、不把支付单写成成功** |
| C54 | 支付赠分 | 订单 `get_points > 0` 才入账，加 `point_member.point` 并写 `point_member_log(remark="订单支付赠送积分", before/after)`；与结清同一 `db.transactional`，回滚则积分不落 |
| C55 | 线下转账 | `offline_pay` 只生成 `offline_payment`（`check_status=0` 待审核、`pay_sn = trade_id`），返回 `trade_state=NOTPAY`，订单保持待付款且 `pay_fee=0`；后台审核结清（`check_status=1` → 走同一 `markPaid`）属 M1 |
| C56 | wxpay 渠道 | M0 为**本地 mock**：不请求真实网关，直接拼 JSAPI 参数包 `appId/timeStamp/nonceStr/package=prepay_id=<trade_id>/signType=RSA/paySign`；`appId` 取 `sys_config.pay.wxpay.appid`，缺省回落 `wxmock0000000000` |
| C57 | PAY-5 应答体 | **不走 `{"data":…}` 包络**，返回渠道约定的 XML：`<xml><return_code>SUCCESS|FAIL</return_code><return_msg>…</return_msg></xml>`；金额不符、`trade_state` 非 SUCCESS、缺 `out_trade_no`、支付单不存在一律 `FAIL`；已结清/已关闭回 `SUCCESS` 以免渠道无限重试 |
| C58 | 关单收单 | 用户取消（ORDER-6）与超时关单（`runClose`）都调用 `closeByOrder` 把该单在途支付单置 `CLOSED`；`CLOSED` 单收到"钱其实付了"的回调只应答成功、**不自动退款**（退款/售后域属 M1，`return_msg` 里写明需人工退回） |
| C59 | 充值单 | PAY-4 产出 `CZ` + 16 位号段（**整串 18 字符**），落 `deposit_trade`（不写 `trade` 表）；赠金按 `deposit_recharge_rule` 档位匹配（seed：充 100 送 10、充 500 送 80），非档位金额 `gift_amount=0`；PAY-2/PAY-3 以 `CZ` 前缀分流，充值结清即本金进 `deposit_amount`、赠金进 `gift_amount` |
| C60 | PAY-1 出参 | 固定 16 键（`order_id/order_type/title/total_fee/pay_fee/order_status/pay_status/trade_state/trade_id/pay_types/deposit/point/auto_cancel_time/receipt_type/shop_id/distributor_id`）；未付款 `pay_fee=0`，`pay_types` 取渠道注册表 `supported`，`point.can_pay = (point >= total_fee)`；订单不存在 404 |
| C61 | 回调面免鉴权 | `api/notify` 前缀在后台链 `permitAll`（渠道不带令牌）；请求体支持 `application/x-www-form-urlencoded`、`text/xml`、`application/xml` 与通配，XML 按叶子节点正则抽取为同名参数（`<![CDATA[]]>` 自动剥离），未知 `trade_id` 也只回 `FAIL` 不回 401 |
| C62 | 账本不变量 | 端到端逐分核对三条：`SUM(trade.pay_fee WHERE SUCCESS) == SUM(orders.pay_fee)`、`pay_status=PAYED` 的单必须 `pay_fee == total_fee`（M0 无部分支付）、每笔 `SUCCESS` 支付单必有 `transaction_id` |
| C63 | 幂等与并发 | 「渠道出参 → `trade.settle` CAS → `markPaid` → 赠分」四步在同一事务内，CAS 抢输的一边抛 422「支付单已结清，请勿重复支付」；重复回调、双击支付、并发关单+支付三类竞态均已钉死为"只结清一次、只回补一次" |

## 9. 实现期语义补充（M0-8 会员与支撑域落地时确认，追加于 §8 之后，不回改上文）

| # | 条目 | 实现口径 |
|---|---|---|
| C64 | `nickname` 来源 | 会员昵称取 `members_info.username`（源系统即把昵称存在该列，不是 `members.username` 的登录名）；MEM-1 同时原样回显 `username`，前端两者同值 |
| C65 | 手机号只出掩码 | MEM-1/2/3 出参里的 `mobile` 一律 `take(3)+"****"+takeLast(4)`；`members.mobile` 是登录凭据，任何读接口都不给全量 |
| C66 | MEM-2 白名单写 | 可改字段=docs §2.6 列出的 10 项；`company_id/user_id/have_consume` 出现即 400「该字段不允许修改」；`mobile` 单独判定：掩码串或与当前号相同**静默忽略**（小程序会把 GET 的整包资料原样 PUT 回来），只有"与当前号不同且不含 `*`"才 400 并把 `errors.mobile` 指向 MEM-3 |
| C67 | 校验类错误用 400 | 资料拒改、地址必填缺失、配置空对象、上传缺字段一律 `ErrorCode.VALIDATION` → HTTP 400 + `errors` 逐字段数组（与 Dingo 参数错误同族）；业务前置条件不满足（号码被占用、非本人号码）才用 422 |
| C68 | MEM-3 换绑 | `PUT /api/h5app/wxapp/member/mobile`：验证码比对 `ecx.security.sms-mock-code`（M0 恒码，MEM-8 不入库），新旧号相同 422、新号已被占用 422；写库是 `UPDATE … WHERE mobile = <旧号>` 的**条件更新**，0 行即抛 422，天然防并发换绑 |
| C69 | MEM-8 出参 | `{mobile(掩码), type, status:1, expire_seconds:300, mock:true, mock_code}`，`type ∈ {login, register, rebind_mobile, reset_password, bind_mobile}`；`mock_code` 只在 `mock=true` 时出现，P1 接真实通道后去掉该键并改为入库发号 |
| C70 | 地址包络与整串 | 地址列表用 `{total_count, list[]}`（§2.4 订单列表同构），不是裸数组；行内除源表列外补 `receiver_address`（省+市+区+`adrdetail` 拼接串）与 `is_def`（1/0 而非布尔），前端直接展示不需再拼 |
| C71 | 地址默认互斥 | 首条地址**无条件置默认**（用户不需要先知道有默认概念）；此后任何 `is_def=1` 都在同一事务里 `clearDefault(userId)` + `markDefault(addressId,userId)`，全局至多一条；编辑不传 `is_def` 时保持原状；删除默认地址后由剩余**最新一条**顶替 |
| C72 | 地址越权=404 | `update/delete` 的 WHERE 同时带 `address_id` 与 `user_id`，越权与不存在同返回 404「地址不存在」，不泄露 id 是否存在 |
| C73 | MEM-4 动词与入参 | `POST` 新增、`PUT /{address_id}` 改、`DELETE /{address_id}` 删；同时兼容源系统"DELETE 带 `{address_id}` 请求体"的写法（两条路径同一实现），前端两种调用都能走 |
| C74 | 收藏快照 vs 实时价 | `members_items_fav` 存收藏当时的 `item_name/item_image/item_price`（=出参 `fav_price`），列表**始终批量 join `items` 出实时 `item_price`**（一次 `listByIds`，无 N+1）；降价提示由两价对比得出，因此下架商品**不静默过滤**，改出 `is_onsale=0` 让前端标注 |
| C75 | MEM-6 幂等 | 收藏前先 `findId(userId,itemId)`，已存在直接回原行（不产生第二行）；取消收藏删不到也不报错（与 PHP 一致的宽松语义），出参恒 `{fav_id, item_id, is_collect:0}` |
| C76 | 浏览足迹 | 同一 `(user_id, item_id)` **先删后插**只留最新一次，列表按 `MAX(created) DESC` 分页；`GET /api/h5app/wxapp/member/browse/items`、`DELETE …/browse/items/{item_id}`、`DELETE …/browse/items`（清空，出参 `{deleted}`）三条是本文新增端点（源系统同语义散在 `member` 控制器）；**匿名浏览不写任何人名下**，GOODS-8 只在带令牌时记录，记录失败不影响详情出参 |
| C77 | GOODS-8 会员价 | `member_price` 不再是回填占位（C13 的 M0-8 待办在此关闭）：`price - price*(100-discount)/100`，与购物车 `MemberDiscountRule` **同一算式同一取整**，因此详情页价格==结算价格；普通会员/匿名 `member_price == price`。`collect` 键带令牌时为 `{fav_id, is_collect}`、匿名为 `null` |
| C78 | MEM-7 与 ORDER-8 同口径 | `statistical` 的 `orderscount` 直接复用 `OrderQueryService.counts`（七键 `notpay/pending_delivery/pending_receive/done/evaluation/cancel/total`），不再独立实现一份 SQL；`point/deposit/totalConsumption` 与 MEM-1 同源同值 |
| C79 | SUP-1 两层合并 | `GET /api/config` 读 `company_id IN (0, 当前租户)`，先铺全局行再用同键租户行**覆盖**，合并视图里同键只出一条且全局行不复制；`PUT` **只写租户层**（全局行只读），数组/对象值序列化成 JSON 文本存 `config_value`（回读得到 `["深圳","北京"]` 这种字符串），空对象 400 |
| C80 | SUP-2 落盘命名 | 存储名一律服务端生成：`{yyyyMM}/{uuid}.{ext}`，**不含原始文件名**（防路径穿越与互相覆盖）；扩展名白名单 `{jpg,jpeg,png,gif,webp,bmp}` + `Content-Type` 必须是 `image/*`，超限/空文件/缺 `file` 字段分别 400；写盘后再次校验解析路径仍在 `ecx.upload.root` 内；成功后 `upload_files` 落台账（`user_id` 可空，供后台审计溯源） |
| C81 | `/storage` 静态面 | `ecx.upload.url-prefix`（默认 `/storage`）注册为资源映射（`file:` 指向 `ecx.upload.root`，启动即建目录），并显式挂在**第三条 SecurityFilterChain**（`@Order(3)`、`permitAll`）上——不依赖"没有链匹配即放行"的默认行为；`<prefix>/**` 在文档里请写成"prefix 通配"以免 Kotlin 嵌套注释（详设 §8 已记一次同类踩坑） |
| C82 | SUP-4 只读审计 | `GET /api/operator/logs` 分页倒序（`ORDER BY id DESC`），过滤入参 `operator_id/keyword(命中 route_name)/start_time/end_time(比对 created)`，出参行固定九键 `id, company_id, operator_id, operator_name, route_name, method, params, ip, created`；**无写接口**（日志不可删不可改，清理属运维脚本）；写路径由 `@Audited(route=…)` 切面留痕，`SUP-2` 上传刻意**不加** `@Audited`——切面会把全部入参序列化进 `params`，二进制文件不应入日志 |
| C83 | 跨面令牌 | 会员令牌打到后台面、后台令牌打到会员面都是 401 `Unauthenticated.`（签发方/受众不匹配即视为未认证，不是 403），与源系统 Dingo 行为一致；权限不足（如 `viewer` 改配置、看日志）才是 403 |

## 10. P0 端点交付勾选（M0 收尾盘点，追加于 §9 之后，不回改上文）

逐条对照 §2 的 47 条 P0 编号（AUTH 5 / GOODS 8 / CART 6 / ORDER 9 / PAY 5 / MEM 8 / SUP 6）。
"验证"列指该端点被哪些测试覆盖：`*EndToEndTest` 是 MockMvc 契约测试（`mvn -B verify` 内跑），`mNN_smoke` 是 fat jar + 文件 H2 的实跑冒烟。

| 编号 | 状态 | 落点 | 验证 |
|---|---|---|---|
| AUTH-1..5 | ✅ 已交付 | `AuthController` / `FrontAuthController` + `JwtService` | `AuthEndToEndTest` 14 例、`m04_smoke` 前置登录段 |
| GOODS-1..6 | ✅ 已交付 | `GoodsAdminController` + `ItemService` | `GoodsEndToEndTest` 12 例、`m04_smoke` 14/14 |
| GOODS-7/8 | ✅ 已交付 | `GoodsFrontController`（8 号带令牌时出 `collect`/`member_price`，见 C77） | `GoodsEndToEndTest`、`m08_smoke` 金卡折让段 |
| CART-1..6 | ✅ 已交付 | `CartFrontController` + `CartService.quote` | `CartEndToEndTest` 12 例 + `PricingPropertyTest`、`m05_smoke` 23/23 |
| ORDER-1..3 | ✅ 已交付 | `CartFrontController`(预览) / `OrderFrontController`(建单、预支付) | `OrderEndToEndTest` 14 例 + `OrderStockConcurrencyTest`、`m06_smoke` 48/48 |
| ORDER-4..8 | ✅ 已交付 | `OrderFrontController` + `OrderQueryService.counts` | `OrderEndToEndTest`、`m06_smoke`、MEM-7 与 ORDER-8 同口径断言（C78） |
| ORDER-9 | ✅ 已交付 | `OrderAdminController`（列表/详情/备注/发货/超时关单） | `OrderEndToEndTest`、`PayEndToEndTest` 第 10 例后台发货 |
| PAY-1..5 | ✅ 已交付 | `PaymentFrontController` + `NotifyController`（回调面免鉴权、XML 应答） | `PayEndToEndTest` 14 例、`m07_smoke` 60/60 + 超时模式 5/5 |
| MEM-1 | ✅ 已交付 | `MemberFrontController` + `MemberService.profile` | `MemberEndToEndTest` 第 2 例、`m08_smoke` §1 |
| MEM-2 | ✅ 已交付 | 同上（白名单写 + 掩码忽略，见 C66） | `MemberEndToEndTest` 第 3 例、`m08_smoke` §2 |
| MEM-3 | ✅ 已交付 | `MemberService.rebindMobile`（条件更新 CAS） | `MemberEndToEndTest` 第 5 例、`m08_smoke` §3 |
| MEM-4 | ✅ 已交付 | `AddressService` + `MemberFrontController`（两种删除写法都收，C73） | `MemberEndToEndTest` 第 6 例、`m08_smoke` §4 |
| MEM-5/6 | ✅ 已交付 | `CollectService`（实时价/快照价双出，C74） | `MemberEndToEndTest` 第 7 例、`m08_smoke` §5 |
| MEM-7 | ✅ 已交付 | `MemberService.statistical` | `MemberEndToEndTest` 第 9 例、`m08_smoke` §7 |
| MEM-8 | ✅ 已交付 | `VcodeService`（M0 恒码，C69） | `MemberEndToEndTest` 第 4 例、`m08_smoke` §2 |
| SUP-1 | ✅ 已交付 | `SupportAdminController` + `ConfigService`（两层合并，C79） | `SupportEndToEndTest` 第 2 例、`m08_smoke` §9 |
| SUP-2 | ✅ 已交付 | 同上 + `UploadService` + `StorageResourceConfig`（`/storage` 链，C81） | `SupportEndToEndTest` 第 3 例、`m08_smoke` §10 |
| SUP-4 | ✅ 已交付 | `OperatorLogService` + `@Audited` 切面（只读，C82） | `SupportEndToEndTest` 第 4 例、`m08_smoke` §11 |
| SUP-5 | ✅ 已交付 | Spring Boot Actuator `/actuator/health`（`management.endpoints` 暴露 health,info） | 实跑 curl 200 `{"status":"UP"}` |
| SUP-3 | ⏸ 按计划留 P1 | 店铺下拉简版：`DistributorShopMapper.listByIds` 已在 M0-5 落地并用于购物车分组/订单出参，独立的 `/api/distributor/list` 端点属 §3 店铺分销 P1 | 现有调用点在 `CartEndToEndTest`/`OrderEndToEndTest` 的店铺名字段断言里 |
| SUP-6 | ✅ 语义等价交付 | 不新增端点：线下转账凭证提交走 **PAY-2 `pay_type=offline_pay`**（只落 `offline_payment`、订单留待付款，见 §8 C55），与源系统"订单详情里点转账"同一业务事实 | `PayEndToEndTest` 线下转账例、`m07_smoke` |

结论：§2 的 P0 编号中除 SUP-3（店铺分销域，P1 已登记）外全部交付并有测试覆盖；M0 的 H5 主链路
「登录→逛→加购→预览→下单→支付→发货→收货→个人中心」在实跑与 MockMvc 两条腿上都已闭环。

## 11. P1 端点规格 —— 售后域（M1-1 开发前补齐，追加于 §10 之后，不回改上文）

源系统售后面极大（后台 12 条 + 会员面 9 条 + 店务端 6 条 + ERP 反向驱动 + 4 类导出/提醒/原因配置）。
M1-1 只交付**交易闭环必需的 17 条**，其余按下表"归属"列延后；换货与导出属 M2。

编号：会员面 `AF-F1..7`，后台 `AF-A1..10`。金额一律分；`aftersales_status`/`progress` 用源系统整数码（见 §11.4）。

### 11.1 会员面（前缀 `/api/h5app/wxapp`，鉴权=会员 JWT，链=frontChain）

| # | 方法 | URL | 说明 | 关键入参 | 出参（`data`） |
|---|---|---|---|---|---|
| AF-F1 | POST | `/aftersales` | 申请售后 | `order_id`(Y,16位串)、`item_id`(Y,订单行内)、`num`(Y,1..可退件数)、`aftersales_type`(Y：`ONLY_REFUND`/`REFUND_GOODS`)、`reason`、`description`、`refund_fee`(N，缺省=按件分摊值)、`return_type`(`logistics`/`offline`)、`contact`/`mobile`(return_type=offline 时 Y) | `{aftersales_bn, aftersales_status, progress, refund_fee, refund_point, num, item_name, can_apply:1}` |
| AF-F2 | GET | `/aftersales` | 我的售后列表 | `page`,`pageSize`(Y)、`aftersales_status`(N,整数精确)、`aftersales_type`(N) | `{total_count, list[]}`，行见 §11.3 |
| AF-F3 | GET | `/aftersales/{aftersales_bn}` | 售后详情 | 路径参 | §11.3 行 + `detail[]`、`refund_info{}`、`order_info{}`(仅本单行摘要)、`status_msg`、`progress_msg`、`sendback_data{}` |
| AF-F4 | POST | `/aftersales/sendback` | 回寄物流填报 | `aftersales_bn`(Y)、`corp_code`(Y,6..30)、`logi_no`(Y) | `{aftersales_bn, aftersales_status, progress:2}` |
| AF-F5 | POST | `/aftersales/close` | 撤销申请 | `aftersales_bn`(Y)、`memo`(N) | `{aftersales_bn, aftersales_status:4, progress:7}` |
| AF-F6 | GET | `/aftersales/item/price` | 单件可退额 | `order_id`(Y)、`item_id`(Y) | `{price: Long}`（= `floor(行 total_fee / 行 num)`，不含运费） |
| AF-F7 | GET | `/aftersales/reason/list` | 申请原因字典 | — | `{list: string[]}`（源系统存 redis；本项目存 `sys_config` 键 `aftersales.reason`，缺省回落四条内置文案） |

### 11.2 后台（前缀 `/api`，鉴权=运营 JWT + `@RequiresResource`，写路径 `@Audited`）

| # | 方法 | URL | 权限码 | 说明 | 关键入参 |
|---|---|---|---|---|---|
| AF-A1 | GET | `/aftersales` | `aftersales.lists` | 售后列表分页 | `page`,`pageSize`；筛选 `aftersales_bn`/`order_id`/`aftersales_status`/`aftersales_type`/`mobile`/`start_time`/`end_time` |
| AF-A2 | GET | `/aftersales/{aftersales_bn}` | `aftersales.detail` | 详情（含 `buttons` 可执行动作） | — |
| AF-A3 | POST | `/aftersales/review` | `aftersales.review` | 审核 | `aftersales_bn`(Y)、`is_approved`(Y,1/0)、`refuse_reason`(required_if 0)、`refund_fee`/`refund_point`(N，缺省回填申请值) |
| AF-A4 | POST | `/aftersales/refundCheck` | `aftersales.refund_check` | 退款确认（终局） | `aftersales_bn`(Y)、`check_refund`(Y,1/0)、`refund_fee`(required_if 1，≤申请额)、`refunds_memo`(required_if 0) |
| AF-A5 | PUT | `/aftersales/remark` | `aftersales.update` | 商家备注 | `aftersales_bn`(Y)、`memo`(Y,≤150) |
| AF-A6 | POST | `/aftersales/apply` | `aftersales.apply` | 代客申请 | 同 AF-F1 + `user_id` 从订单带出；`ONLY_REFUND` 当场审核通过 |
| AF-A7 | POST | `/aftersales/sendback` | `aftersales.update` | 代填回寄 | 同 AF-F4 |
| AF-A8 | GET | `/refund` | `refund.lists` | 退款单列表 | `page`,`pageSize`；筛选 `refund_status`/`refund_type`/`order_id`/`refund_bn`/时间区间 |
| AF-A9 | GET | `/refund/detail/{refund_bn}` | `refund.detail` | 退款单详情 | — |
| AF-A10 | POST | `/refund/offline` | `refund.offline` | 线下打款登记（银行七字段→直接 SUCCESS） | `refund_bn`(Y)、`payee_name`/`bank_name`/`bank_account`/`bank_branch`/`bank_province`/`bank_city`/`memo` |

延后归属：导出（`logExport`/`financialExport`）、提醒配置（`remind`）、店务端 6 条（M1-4 员工端链）、ERP 反向驱动（M2）、换货发出 `sendConfirm`（M2）、原因配置写接口（M1-4 一并做读+写）。

### 11.3 出参行字段（列表与详情共用）

`aftersales_bn`(串), `order_id`(串), `item_id`, `item_name`, `item_bn`, `pic`, `num`, `refund_fee`, `refund_point`,
`aftersales_type`, `aftersales_status`, `progress`, `status_msg`, `progress_msg`, `reason`, `description`,
`refuse_reason`, `memo`, `evidence_pic`(数组), `return_type`, `contact`, `mobile`(掩码), `freight`,
`company_id`, `user_id`, `distributor_id`, `created`, `updated`, `sendback_data`(对象或 null)。
详情另加 `detail[]`（`aftersales_detail` 行：`detail_id, item_id, item_name, pic, num, refunded_num, refund_fee, aftersales_status, progress`）、
`refund_info`（`aftersales_refund` 行或 null：`refund_bn, refund_status, refund_type, refund_channel, refund_fee, refunded_fee, trade_id, pay_type, refund_id, refund_success_time`）、
`order_info`（`order_id, order_status, pay_status, total_fee, pay_fee, item_fee, freight_fee`）。

### 11.4 状态与进度码（照抄源系统整数码，不做重命名）

`aftersales_status`：0=待处理 1=处理中 2=已处理 3=已驳回 4=已撤销（明细另有 5=审核中）。
`progress`：0=等待商家处理 1=接受申请待回寄 2=消费者已回寄 8=商家确认收货待退款 9=退款处理中 3=已驳回 4=已处理 7=已撤销 6=退款完成 5=退款驳回。
`aftersales_type`：`ONLY_REFUND` 仅退款 / `REFUND_GOODS` 退货退款（M1-1 实现两类；`EXCHANGING_GOODS` 换货在 AF-F1/AF-A6 一律 501「换货流程属 M2」，与 D5/D6 同口径）。
`refund_status`：`READY` → `AUDIT_SUCCESS` → `SUCCESS`；异常态 `REFUSE`（驳回）/`CANCEL`（撤销）/`CHANGE`（渠道退款失败，留痕不抛错）。

### 11.5 与源系统的差异（D 系列续编）

| # | 变更 | 理由/缓解 |
|---|---|---|
| D7 | **一售后单=一订单行 N 件**，不采用 `shopApplyByNum` 的"一件一单"拆单 | 拆单是为 ERP 逐件入库，本项目 `refunded_num` 按申请件数整体入库；守恒口径不变（`Σrefund_fee ≤ 行 total_fee`） |
| D8 | 申请原因存 `sys_config`（`aftersales.reason`）而非 redis | M1 无 redis 依赖；AF-F7 读不到时回落内置四条 |
| D9 | 手机号 `contact/mobile` 明文列 + 出参掩码，不做 `fixedencrypt` 落库 | 加密存储属数据合规专项（M2），列宽已按密文预留 |
| D10 | 售后**不改** `trade.trade_state`（只累加 `trade.refunded_fee`） | 与源系统一致：售后单退成功不把支付单改成 REFUND，售前取消才走 `REFUND_PROCESS/REFUND_SUCCESS` |

## 12. 实现期语义补充（M1-1 售后域落地时确认，追加于 §11 之后，不回改上文）

§11 是开发前规格，落地时以下口径被实测钉死或改写，编号续 C83 之后。验收：`AftersalesEndToEndTest` + `tools/m11_smoke.py`（见 docs/05 §6 M1-1 记录）。

| # | 条目 | 实现口径 |
|---|---|---|
| C84 | 申请准入 | `pay_status ∈ {PAYED, REFUND_PART}` 才能发起；`NOTPAY/CANCEL` → 422「订单未支付，不能申请售后」。**REFUND_PART 必须放行**——部分退款后剩余件数还要能继续退，这一条是门槛用例的前置；整单退完（`REFUND`）不再放行也无妨，件数闸门本身就会 422 |
| C85 | `left_aftersales_num` 只重算不增量 | 权威口径在 `aftersales.occupiedNum`（占额度态 `aftersales_status IN (0,5,1,2)` 的 `SUM(num)`），这一列只是镜像：每次状态迁移按 `Σ行件数 − 占用件数` **整值重算后 CAS 写回**，读到 0 的历史单走 `backfill`。绝不做 `+1/-1`，否则一次并发就把镜像跑飞 |
| C86 | `orders.pay_status` 按金额守恒判定 | 退款成功后取 `SUM(成功退款 refund_fee + refund_point)`，`≥ pay_fee` → `REFUND`，否则 `REFUND_PART`。**不看售后行数也不看行 `aftersales_status`**：一行退完就变 `REFUND_SUCCESS`，用行数判会把"两行退了一行"误判成整单退完 |
| C87 | READY 不占额度 | `refund_status=READY` 只是"待财务确认"，`AF-A9` 的 `trade_can_refund` 仍等于实付、`order_refunded_fee` 仍为 0；生成退款单 ≠ 退钱 |
| C88 | 额度占用点在 AF-A4 | `trade.refunded_fee` 只在退款确认时累加，AF-A10 登记只推状态不再累加。两道闸门写在 SQL 的 `WHERE` 里（`refunded_fee + ? ≤ pay_fee`、行 `refunded_fee + ? ≤ total_fee`），影响 0 行 → 422 + 整笔回滚，退款单留在 `READY` 等重试（e2e 第 10 例改窄额度各撞一次并断言回到原位） |
| C89 | 渠道失败不抛错 | 通道返回失败 → 事务整体回滚，随后单独记一条 `CHANGE` 留痕并回 200，让运营看得见"退失败"而不是接到 500 后反复点 |
| C90 | AF-A10 登记的条件更新 | 七字段 `UPDATE … WHERE refund_status='AUDIT_SUCCESS'`，0 行即 422「退款单状态已变更」；备注分两路写：售后单记流程（`线下打款完成`），退款单记登记原文（`memo` 不被收尾覆盖），两边语义不互相污染 |
| C91 | 守门顺序 | 状态守门**先于**参数校验：非 `AUDIT_SUCCESS` 的单即使缺 `bank_account` 也是 422 而不是 400。HTTP 侧造不出 `AUDIT_SUCCESS`（mock 渠道当场成功），因此冒烟只验 `refund_bn` 格式 400 / 404 / 422，登记成功分支由 e2e 第 17 例用 SQL 把支付单标成 `offline_pay` 走真实 deferred 路径覆盖 |
| C92 | 面鉴权矩阵 | 会员令牌打后台面、后台令牌打会员面都是 401；`viewer` 缺权限码 403；别人的售后单在会员面按 **404** 处理（不泄露存在性）；非数字 `aftersales_bn`/`refund_bn` 一律 404，`toLongOrNull()` 兜底不进 SQL 层（避免 MyBatis 反射异常变 500） |
| C93 | 明细与主表同步 | `aftersales_detail` 的 `aftersales_status/progress` 由同一条 `transit` 批量迁移，命中 0 行即视为数据不一致并回滚（e2e 第 19 例审计"主表与明细永远同步"） |
| C94 | ORDER-8 分桶口径修正（M1-1 引出） | `pending_delivery` 改按**单头态**判：`order_status IN ('PAYED','PACKING') AND delivery_status IN ('PENDING','PART_DONE','PACKED')`，ORDER-4 的 `status=6` 页签同步去掉 `pay_status='PAYED'`。原因：退款只改 `pay_status`（D10/§3），`order_status` 保持 `PAYED`，旧口径让退款中的单从六个桶里同时消失，直接破掉 C43 的「分桶互斥且求和恒等 `total`」。e2e 第 20 例把这条钉成回归用例 |

### 12.1 与源系统的差异续编（D 系列，追加于 §11.5 之后）

| # | 变更 | 理由/缓解 |
|---|---|---|
| D11 | **不新增"确认收货"端点**：对 `progress=2`（消费者已回寄）的单再调一次 AF-A3 即等价于商家确认收货 | 源系统 `confirmReceive` 独立接口 + 独立权限码；本项目审核只有两条出口（同意/驳回），确认收货就是"同意"的第二步，状态机少一条箭头、鉴权少一个点，`refunded_num` 按申请件数整体入库 |
| D12 | 退款单只在进入 `progress=8`（待退款）时生成 | 退货退款在待回寄（1）阶段生成退款单没有意义，驳回还要作废一行；"未审先退"的记录噪音直接消除。AF-F3/AF-A2 的 `refund_info` 在该阶段回 `null`，前端据此判断是否显示退款区块 |
| D13 | 额度占用点在退款确认而非审核同意 | 与 D10 同源：`trade.refunded_fee` 是"真退出去的钱"，审核通过不等于钱动了。`AF-A9` 的 `trade_can_refund` 因此在 READY 阶段仍是全额 |
| D14 | 渠道退款失败回 200 + `CHANGE` 留痕 | 源系统抛 `ResourceException` 后无落库痕迹，运营端看不到失败原因；本项目失败也是状态，必须可见 |
| D15 | 售后不改 `order_status`，只在 `pay_status` 与行 `aftersales_status` 上标注 | 保持与源系统一致，代价是页签/角标必须按单头态分桶（见 C94），否则退款单在买家侧"消失" |
| D16 | 佣金与返利在同一事务内按行货款占比冲销 | `commission_fee`/`total_rebate` 只减不借（负数封顶 0）；源系统在支付回调里异步冲销，本项目放进退款事务，保证守恒审计读到的永远是已收敛状态（e2e 第 10 例逐笔断言 900/600 递减到 0/0） |

## 13. 实现期语义补充（M1-2 真实渠道接入落地时确认，追加于 §12 之后，不回改上文）

M1-2 接进两条真渠道（微信支付 v3、支付宝）与线下转账审核面。以下口径由实测钉死，验收证据：`WxPayV3SimulationTest` + `AliPaySimulationTest`（各 8 例，本地仿真网关真签真验）+ `tools/m12_smoke.py`（58 项，默认 mock profile 的契约回归）。

### 13.1 本轮新增/改写的端点

| # | 方法 | URL | 说明 |
|---|---|---|---|
| PAY-5 | POST | `/api/notify/wxpay` | 同一条映射现在**同时**接微信 v2（form/XML 报文）与 v3（JSON + `Wechatpay-*` 头），按报文内容分流 |
| PAY-5b | POST | `/api/notify/alipay`（webhook 组，匿名放行） | 支付宝异步通知：form-urlencoded，应答体是**纯文本** `success`/`failure`（非 data 包络、非 XML） |
| SUP-6a | GET | `/api/offline-payments`（后台，`offline_payment.lists`） | 转账凭证待审列表：`check_status`（0 待审/1 已审/2 已拒）与 `order_id` 均可筛，分页 |
| SUP-6b | POST | `/api/offline-payments/check`（后台，`offline_payment.check`，`@Audited`） | 审核：`check_status=1` 通过并结清、`=2` 驳回。**唯一把钱收进来的入口** |

不单开凭证详情端点：列表行已含 `voucher_pic/pay_sn/transfer_remark/operator_name` 全字段，`?order_id=` 就是钻取，详情只是同一份数据的第二次读。

### 13.2 实现期口径（C 系列续编，追加于 §12 之后）

| # | 条目 | 实现口径 |
|---|---|---|
| C95 | 回调映射只注册一条 | `/api/notify/wxpay` 与 `/api/notify/{channel}` 合并在同一个 `@PostMapping`，**按报文内容分流**（`{` 前缀 = v3 JSON 信封，其余按表单/XML）。用 `consumes` 拆两条映射会炸：Spring 对**空 body** 的请求一律视为 `consumes` 匹配，`curl -X POST /api/notify/wxpay` 同时命中两条 → `IllegalStateException: Ambiguous handler methods` → 500，而 500 的应答体不是渠道认识的格式，渠道按"服务端故障"无限重试 |
| C96 | 渠道未注册 = 从清单消失，不是报错 | `registry.supported` 驱动 PAY-1 的 `pay_types`；`ecx.pay.active-channel-profile` 没开 alipay 时它根本不出现在清单里，硬发起则 501「暂不支持该支付方式：alipay」。前端据此隐藏按钮，不需要额外的开关接口 |
| C97 | 回调应答体由渠道决定，控制器只兜底 | `NotifyResult.formAck` 非空时**原样**回给渠道：微信 v2 = `<xml><return_code>`，支付宝 = 纯文本 `success`/`failure`（`text/plain`，判的是逐字节相等而非 contains），微信 v3 = HTTP 200/500 + `{"code","message"}`（手工拼，不经全局 Jackson 配置）。只有渠道没解析出来（未知渠道、异常兜底）才退回微信 v2 的 XML 口径 |
| C98 | 进渠道验签的报文一个字节都不能改 | 渠道签名覆盖"收到的全部参数"（支付宝 V1 = 除 `sign`/`sign_type` 外按 key 升序拼 `k=v&`，微信 v2 的 MD5 同理）。控制器曾把路径变量 `channel` 塞进参数 map，结果**真通知永远验不过**、被支付宝按 4m/10m/… 无限重投。渠道身份现在作为独立入参传给 `notifyForm`，验签通过之后才补进结清参数 |
| C99 | 支付宝应答验签的是"节点子串" | `JsonConverter.getSignSourceData` 用 `apiMethodName.replace('.','_')+"_response"` 定位（要求 `indexOf > 0`，应答必须以 `{` 开头），再按 `start = index + key.length + 2`、花括号配对截断，把**那段原文**交给 `SignChecker.check`。自造网关必须先拼好节点文本、对节点签名、最后套外壳，顺序反了必然验不过；同一 key 出现两次会被判"应答报文重复"直接抛错 |
| C100 | 支付宝 SDK 4.39.x 的请求形态：公共参数在 query | `method`/`sign`/`app_id`/`timestamp` 挂在 URL query，POST body 里只有 `biz_content`，而待签串是**两处合并后的全参数**。网关或夹具只读 body 就既取不到 `method`、也验不过签名（本轮 6 例下单全挂在这一条上） |
| C101 | `AlipayResponse.isSuccess()` 只看 `sub_code` | 本版本实现是 `StringUtils.isEmpty(subCode)`，`code=10000` 不参与判断。业务失败必须落 `sub_code`，成功节点里一个 `sub_*` 字段都不能有 |
| C102 | 渠道状态必须归一化，判据才能只有一条 | 支付宝原话 `TRADE_SUCCESS/TRADE_FINISHED→SUCCESS`、`TRADE_CLOSED→CLOSED`、`WAIT_BUYER_PAY→NOTPAY`；回调与补单共用 `trade_state == "SUCCESS"` 一条判据。不归一化的后果是"报文完全正常、掉单永远补不上"——最难查的一种漏 |
| C103 | 金额单位换算只活在渠道内部 | 支付宝全程「元、两位小数」字符串、微信全程「分」，本系统与 `trade` 一律整分，`fenToYuan/yuanToFen` 不在渠道外出现。回调金额**只做校验**，应付额永远取本地 `trade.total_fee`（M0-7 的不变式，接真渠道后继续成立） |
| C104 | 补单两条腿与 `channel_closed` | 真渠道有本地造不出的两个单边态：掉单（钱在渠道扣了、回调没进来）与关单不彻底（本地 `CLOSED` 但渠道那张预支付单还活着，超时窗口内扫码照样付得出去）。`PayReconcileJob` 每 5 分钟只扫 `pay_type in (wxpay,alipay)`、带 120s 宽限：前者查到 `SUCCESS` 就**复用 `PayService.notify`**（CAS 幂等、金额核对、积分入账与回调一字不差，两套结清逻辑迟早会漂，这里只有一套）；后者向渠道发 close，渠道答"已支付/查无此单"也算终局，成功或终局才置 `channel_closed=1` 停止重试。答"已支付"的那笔不会丢钱——下一轮以 `SUCCESS` 落进第一条腿 |
| C105 | 微信 v3 通知的新鲜度由应用判 | SDK 的 `NotificationParser` 不做时间窗校验，应用侧补 `Wechatpay-Timestamp` 超窗（±300s）即 FAIL。同一笔靠 CAS 结不清第二次，但退款回执等其他密文不该有被无限重放的余地 |
| C106 | 未绑定一律拒发，不做"无主支付" | 微信 JSAPI 必需 `payer.openid`、支付宝 `trade.create` 必需 `buyer_id`（2088 开头的支付宝 userId，不是登录号），两条口径完全一致：先按租户+会员反查 `members_associations`（`user_type='wechat'/'alipay'`），查不到才允许应用级兜底值（`fallback-open-id`/`fallback-buyer-id`，生产必须留空），两个都没有 → 422「该会员未绑定…」 |
| C107 | 退款一渠道一形态 | 支付宝 `trade.refund` **同步**给结果（`fund_change=Y` 当场 `SUCCESS`，没有"等回执"这一态）；微信 v3 的 `PROCESSING` 映射成 `RefundOutcome.pendingCallback`，退款单原地停在 `AUDIT_SUCCESS`，由 `REFUND.SUCCESS` 回执推到终态，而不是人工登记。`out_refund_no`/`out_request_no` 都用退款单号，渠道按它幂等，CAS 重放不会退两次 |
| C108 | 线下审核复用同一条结清 CAS | `check_status` 的条件更新只从 0 出发（一凭证一结论，重复点"通过"422）；少收 422 并提示按应付全额结清；驳回只标凭证、订单原样留在待付款，不产生任何资金动作。审核通过走的正是回调那条 `trades.settle` CAS，所以"审核通过 + 重复通知"不会结两次 |

### 13.3 与源系统的差异续编（D 系列，追加于 §12.1 之后）

| # | 变更 | 理由/缓解 |
|---|---|---|
| D17 | 真渠道密钥只走应用配置，不进库、不进 `sys_config` | `ecx.pay.{wxpay,alipay}.*` 全部由环境变量/配置中心下发（内联 PEM 或文件路径二选一，路径优先）。M1-2 口径是**一套商户号服务一个租户**，多商户映射属 M2 开放能力，因此配置走应用级而不是租户级 |
| D18 | 沙箱真单用本地仿真等价替代，并且明写没做完 | 商户号、密钥、公网回调地址在本地拿不到，于是自签双密钥（`ecshopx-boot/src/test/resources/sim/mockwx.p12`，两把 RSA-2048：`merchant`/`platform`）+ 本地假渠道服务器把"真签真验"补齐：SDK 用商户私钥真签请求，夹具用商户公钥真验；夹具用平台私钥真签应答与通知，SDK/渠道代码用平台公钥真验，伪造签名、改包、错 `app_id`、密钥填反、乱序与重复通知全部有对应用例。**剩下的真单验证仍是上线前 TODO**：微信商户平台与支付宝沙箱各跑一笔，验的是配置项与证书序列号，代码不会再改 |
| D19 | 两个仿真夹具的传输层不一样，是渠道要求不是随手挑的 | 微信 `HttpHeaders` 区分大小写（`Wechatpay-Signature` 等），JDK 的 HTTP 实现会规范化头名让 SDK 取不到签名 → 微信夹具必须裸 `ServerSocket`；支付宝只验应答正文里的节点子串，头名无所谓 → 可以用 JDK `HttpServer`。SDK 域名来自不可改的 `HostName` 枚举，接缝是 `ecx.pay.wxpay.base-url`：OkHttp 拦截器只换 scheme+host+port，**路径与查询串原样保留**（签名覆盖的正是"方法+路径+查询串+body"），换沙箱网关零代码改动 |
| D20 | 出参键位不做假统一 | 微信 JSAPI 仍回 `appId/timeStamp/nonceStr/package/signType/paySign` 六键（PAY-2 契约不变、前端零改动，`paySign` 由 SDK 用商户私钥真签，`MOCK-` 假签名在这个 profile 下不可能出现）；支付宝 `trade.create` 只创建交易、前端用 `my.tradePay({tradeNO})` 调起，因此如实回 `trade_no/out_trade_no/total_amount`。硬凑成同一套键位只会让前端两套逻辑都要写 if |
| D21 | 冒烟脚本对"待付款残单"改用差值口径 | m07 断言「清理后本人无待付款残单 = 0」是**全局归零**，而回调失败/凭证驳回的单本该留在 `NOTPAY`。七脚本同库顺序跑（m04→m12）时，m11 刻意留一笔未支付单验「未付款不能申请售后」，m12 若照抄全局口径就会被前一脚本的在途单踩出假 FAIL。m12 因此进场先记基线、收口后断言**回到基线**，并把自己造的残单全部取消 |

## 14. P1 端点规格 —— 营销域（M1-3 开发前补齐，追加于 §13 之后，不回改上文）

范围按 docs/05 §M1-3：**优惠券 + 满减满折 + 秒杀 + 拼团**，四项都挂进同一条算价链且与预览同源。
不做（P2）：满赠、加价购扩展、砍价、签到/任务/抽奖、人群定向折扣、导购券、券转赠、兑换券核销、分类维度圈品（`use_bound=2`）。

### 14.0 盘点结论：源系统落位与本项目落位

| 本项目表（已存在） | PHP 落位 | 备注 |
|---|---|---|
| `promotions_coupon` / `promotions_coupon_user` | `kaquan_discount_cards` / `kaquan_user_discount`（KaquanBundle） | 源系统的券**不在 promotions 库**，本项目按 schema.sql 注释里"统一卡券表"落位 |
| `promotions_marketing_activity(+_items)` | 同名（`MarketingActivity`，规则列是 **`condition_value`**） | docs/05 写的 `discount_config` 属于另一张表 `promotions_activity`（会员定向自动营销），RuleParser 两种存量形态都要认 |
| `promotions_seckill_activity` / `_rel_goods` | 同名（`SeckillActivity`/`SeckillRelGoods`） | PHP 库存池在 Redis，本项目无 Redis |
| `promotion_groups_activity/_team/_team_member` | 同名（**单数 promotion**，与 seckill 的复数前缀不同，沿用源系统列名） | 团号 PHP 是 16 位拼串 |

仓库侧既有挂接点：`PriceContext`/`PriceRule` 责任链（M0-5）、`orders_cart.activity_type/activity_id`（CART-1 已接收）、`orders_normal_orders.act_id/order_class`、`orders_normal_items.coupon_discount/point_fee`、`orders_associations.coupon_discount`、`orders_rel_promotions`（** Kotlin 侧零引用，M1-3 起用它落优惠快照**）、`OrderCreateService.payloadOf` 里 `"team_id" to null` 的预留位、`PointRate.POINT_PER_FEN=1`、seed 里 `point.deduct.max_ratio=50` 与一张"满100减20"券。

### 14.1 会员面（前缀 `/api/h5app/wxapp`，鉴权=会员 JWT，链=frontChain）

| # | 方法 | URL | 说明 |
|---|---|---|---|
| CP-F1 | GET | `/coupons` | 可领券列表（分页）：`coupon_id,coupon_name,coupon_type,discount_fee,min_order_fee,valid_text,total_num,sent_num,received_num,can_receive,reason` |
| CP-F2 | POST | `/coupons/receive` | 领取：`coupon_id`(Y)。CAS 抢量 + 每人限领 + 有效期起算，出参 `{coupon_user_id,coupon_code,expire_time}` |
| CP-F3 | GET | `/coupons/my` | 我的券：`status`(`UNUSED`/`USED`/`LOCKED`/`EXPIRED`/缺省全部，`EXPIRED` 含"未用但已过期")分页 |
| CP-F4 | GET | `/coupons/usable` | 结算页可用券：入参与 ORDER-1 同（`cart_type`/`items`/`shop_id`），出参 `usable[]{coupon_user_id,coupon_code,coupon_name,discount_fee,threshold_fee}` + `unusable[]{…,reason}` |
| MK-F1 | GET | `/marketing/activities` | 进行中满减满折活动：`marketing_id,marketing_type,marketing_name,promotion_tag,condition_text,start_time,end_time,use_bound` |
| MK-F2 | GET | `/marketing/activities/{marketing_id}` | 活动详情 + 圈定商品（`use_bound=1` 时列 `items[]`） |
| SK-F1 | GET | `/seckill/activities` | 秒杀场次：`seckill_id,activity_name,activity_start_time,activity_end_time,date_status(pending/doing/end),items_count` |
| SK-F2 | GET | `/seckill/activities/{seckill_id}/items` | 场次商品：`item_id,item_title,pic,activity_price,origin_price,sales_store,available,limit_num,bought_by_me,is_show` |
| MK-F3 | GET | `/items/{item_id}/promotions` | 单商品可享的全部营销（满减 tag + 秒杀价），供商详页角标 |
| GP-F1 | GET | `/groups/activities` | 拼团活动列表：`groups_activity_id,act_name,goods_id,act_price,origin_price,person_num,store,begin_time,end_time,team_count` |
| GP-F2 | GET | `/groups/activities/{groups_activity_id}` | 详情 + 进行中的团 `teams[]{team_id,join_person_num,person_num,lack_num,end_time,head_nickname}` |
| GP-F3 | GET | `/groups/teams/{team_id}` | 团成员（脱敏）：`team_id,team_status,person_num,members[]{user_id,nickname(掩码),avatar,join_time,paid,is_head}` |
| GP-F4 | GET | `/groups/my/teams` | 我参与的团（含我发起的）：`teams[]{team_id,team_status,lack_num,end_time,order_id}` |

### 14.2 后台（前缀 `/api`，鉴权=运营 JWT + `@RequiresResource`，写路径 `@Audited`）

| # | 方法 | URL | 权限码 | 说明 |
|---|---|---|---|---|
| CP-A1 | POST | `/coupons` | `promotions.coupon.update` | 建券（面额/门槛/总量/有效期两种形态/限领） |
| CP-A2 | GET | `/coupons` | `promotions.coupon.lists` | 列表：`coupon_name`(模糊)、`coupon_type`、`status`，分页带 `sent/used` 统计 |
| CP-A3 | GET | `/coupons/{coupon_id}` | `promotions.coupon.lists` | 详情 + 发放/锁定/已用/过期四计数 |
| CP-A4 | PUT | `/coupons/{coupon_id}` | `promotions.coupon.update` | 编辑；面额/门槛改动**不回溯**已发券（券快照列） |
| CP-A5 | PUT | `/coupons/{coupon_id}/status` | `promotions.coupon.update` | 停发/恢复（`status` 布尔） |
| CP-A6 | DELETE | `/coupons/{coupon_id}` | `promotions.coupon.update` | 删除；`send_num>0` → 422，只能停发 |
| CP-A7 | GET | `/coupons/{coupon_id}/records` | `promotions.coupon.lists` | 领取/使用记录（分页，含会员昵称与券状态） |
| CP-A8 | POST | `/coupons/send` | `promotions.coupon.send` | 后台发券：`coupon_id` + `user_ids[]`，或 `grade_id`（按 `promotions_activity.discount_config` 的等级发券映射） |
| MK-A1 | POST | `/marketing/activities` | `promotions.marketing.update` | 建活动（含阶梯规则，服务端校验 JSON 形态） |
| MK-A2 | GET | `/marketing/activities` | `promotions.marketing.lists` | 列表：`date_status`/`check_status`/`marketing_type` 过滤 |
| MK-A3 | GET | `/marketing/activities/{marketing_id}` | `promotions.marketing.lists` | 详情（规则原样回 + 解析后的阶梯） |
| MK-A4 | PUT | `/marketing/activities/{marketing_id}` | `promotions.marketing.update` | 编辑（重新审核后回到 `non-reviewed`） |
| MK-A5 | PUT | `/marketing/activities/{marketing_id}/items` | `promotions.marketing.update` | 圈品：全量覆盖 `items[]{item_id,item_name,price,promotion_tag,is_show}` |
| MK-A6 | PUT | `/marketing/activities/{marketing_id}/check` | `promotions.marketing.check` | 审核：`check_status=reviewed/unreviewed`（只有 reviewed 参与算价） |
| MK-A7 | DELETE | `/marketing/activities/{marketing_id}` | `promotions.marketing.update` | 删除活动 + 其圈品行 |
| SK-A1 | POST | `/seckill/activities` | `promotions.seckill.update` | 建场次（`validity_period` 分钟、`limit_money` 单笔限额） |
| SK-A2 | GET | `/seckill/activities` | `promotions.seckill.lists` | 列表（`date_status` 过滤） |
| SK-A3 | GET | `/seckill/activities/{seckill_id}` | `promotions.seckill.lists` | 详情 + 商品行（含 `hold_store`） |
| SK-A4 | PUT | `/seckill/activities/{seckill_id}` | `promotions.seckill.update` | 改场次（时间/限额/名称） |
| SK-A5 | PUT | `/seckill/activities/{seckill_id}/status` | `promotions.seckill.update` | 停用/恢复（`disabled`） |
| SK-A6 | PUT | `/seckill/activities/{seckill_id}/goods` | `promotions.seckill.update` | 商品行 upsert：`items[]{item_id,activity_price,activity_store,limit_num,is_show}`；已产生销量的行**只允许加库存** |
| SK-A7 | GET | `/seckill/activities/{seckill_id}/report` | `promotions.seckill.lists` | 场次销量报表（按行 `sales_store`/`hold_store`/金额） |
| GP-A1 | POST | `/groups/activities` | `promotions.groups.update` | 建拼团活动（`person_num/limit_time/robot/limit_buy_num`） |
| GP-A2 | GET | `/groups/activities` | `promotions.groups.lists` | 列表 |
| GP-A3 | GET | `/groups/activities/{groups_activity_id}` | `promotions.groups.lists` | 详情 + 团统计（进行/成/败） |
| GP-A4 | PUT | `/groups/activities/{groups_activity_id}` | `promotions.groups.update` | 编辑（`store` 只允许加） |
| GP-A5 | PUT | `/groups/activities/{groups_activity_id}/status` | `promotions.groups.update` | 停用 / 强制结束（PHP `finish/{id}`：结束 = 未成团队立即失败退款） |
| GP-A6 | GET | `/groups/teams` | `promotions.groups.lists` | 团列表：`act_id`/`team_status` 过滤，分页 |

新增权限码 27..40（`sql/seed.sql` 末尾追加，挂 role 1）：`promotions`(catalog) + `promotions.coupon.lists/update/send` + `promotions.marketing.lists/update/check` + `promotions.seckill.lists/update` + `promotions.groups.lists/update`。

### 14.3 结算与下单入参增量

| 端点 | 新增入参 | 语义 |
|---|---|---|
| ORDER-1 / CART-2 / CP-F4 | `coupon_code`、`not_use_coupon`、`point_use` | 从 M0 的开关位变成**真产生金额**（撤销 C25 的"恒 0"口径，在 §15 以新 C 记录） |
| ORDER-2/3 | `order_class`(`normal`/`seckill`/`groups`)、`act_id`、`team_id` | `order_type` 仍只接受 `normal`（`service` 等一律 501，M0 的守卫不动）；秒杀/拼团走 `order_class`，与 PHP 同列 |
| ORDER-2/3 出参 | `team_id` | 开团/参团成功才回，普通单恒 `null`（`payloadOf` 的预留位落地） |

### 14.4 满减满折规则：RuleParser 兼容的存量 JSON

规则文本存在 `promotions_marketing_activity.condition_value`（**元**为单位的字符串金额，与 PHP 一致；本项目内部一律分，换算只发生在解析处）。阶梯数组按 `full` 升序，取**最高命中档**。

| `marketing_type` | 存量形态（PHP 原样） | 解析结果 |
|---|---|---|
| `full_minus` | `[{"full":"100.00","minus":"20.00"},{"full":"200.00","minus":"50.00"}]` | 满 100 减 20 / 满 200 减 50（分：`10000→2000`） |
| `full_discount` | `[{"full":"100.00","discount":"85"}]` | 满 100 打 85 折（`discount` 是百分数 1..<100，折让 = 基数 ×(100-85)/100 向下取整） |
| `plus_price_buy` | `[{"full":"100.00","price":"10.00"}]` + `gifts[]` | **认得但不参与算价**（加价购 P2） |
| `full_gift`/`single_gift` | `[{"full":"100.00"}]` | 认得但不参与算价（满赠 P2） |
| `self_select` | `[{"full":"100.00","num":"2"}]` | 认得但不参与算价 |

`condition_type` 决定基数口径：`totalfee` → 组内应付金额（分）；`quantity` → 组内件数。
`canjoin_repeat=true`（上不封顶）→ 折让 ×`floor(基数/full)`；`false` → 只按最高命中档算一次。
脏数据口径：`condition_value` 空/非法 JSON/负数/`full<=0` → 该活动视为未配置，**不报错、不产生优惠**（存量脏行不能把整条算价链打挂）；`full_minus` 里 `minus>full` 的档丢弃。
另一形态 `promotions_activity.discount_config` = `{"coupons":{"<grade_id>":[{"id":<coupon_id>,"count":n}]},"goods":{…}}`，只在 CP-A8「按等级发券」里读，与满减无关 —— RuleParser 两种都解析，文档 §14.0 已说明列名漂移。

### 14.5 算价链：固定顺序与叠加口径（门槛用例的坐标系）

```
10 GroupingRule        店铺分组（不变）
15 ActivityPriceRule   秒杀/活动价覆盖行单价（新增；只改 l.price，下游一律以覆盖后为基准）
20 MemberDiscountRule  会员等级折让（不变，逐行 floor）
30 FullReductionRule   满减 / 满折（实装，按组匹配档位；free_postage 命中则组运费=0）
40 CouponRule          优惠券（实装，按组抵扣 + 逐行分摊）
50 PointDeductRule     积分抵扣（新增，受 point.deduct.max_ratio 约束）
60 FreightRule         运费（不变，读 30 写的 freePostage）
90 TotalSyncRule       汇总钳制（不变，钳制扩展到三类抵扣各自不得为负、合计不得超过行小计）
```

基数一律是"当前已折让后的应付"，所以叠加顺序 = 上面这张表，且**每一步都可复现**：
- `行应付 = item_fee − discount_fee − coupon_fee − point_fee`（`PriceLine` 新增后两类字段，`total_fee` 口径随之扩展；活动/秒杀/满减继续走 `discount_fee`）；
- 满减、券、积分三类抵扣**都逐行分摊**（按行应付占比 floor，余数给应付最大的那一行），因此 `Σ行 total_fee == 组 total_fee == 单 total_fee` 恒成立 — 售后按件分摊（M1-1）不会把积分/券抵扣重复退一次；
- 券：门槛与抵扣都只比"券所在组的应付"（M0-6 已限定一单一店，`groupCount>1` 时券不生效并在 CP-F4/ORDER-1 里给出 `reason`）；抵扣 = `min(面额, 组应付)`，运费不参与（与 PHP 现金券排除运费一致）；
- 积分：`抵扣上限(分) = min(可用积分 × 1分/积分, floor(剩余应付 × max_ratio%))`，`point_use = point_fee`（1 积分 = 1 分，`PointRate` 不变）；`pay_type=point` 是 C52 的**全额兑换**，与抵扣互斥 — 已用抵扣的单再选积分支付 → 422；
- ORDER-1 出参落实 `coupon_discount/coupon_info[]{id,coupon_code,info,type,rule,discount_fee}/point_fee/user_point/max_point/limit_point`，`discount_info[]` 每项 `type` 取 `full_minus`/`full_discount`/`seckill`/`coupon`/`point`（PHP 同字段，M0 时恒空）。

预览==下单不变：`ActivityPriceRule`/`FullReductionRule`/`CouponRule`/`PointDeductRule` 需要的活动、券、余额**全部由 `CartService.buildContext` 预取进 `PriceContext`**（含 `now`），规则链本身仍然不查库、不取时间、无随机 — M0-5 的属性测试继续有效。

### 14.6 生命周期与定时

| 对象 | 状态机 | 迁移点 |
|---|---|---|
| 券 `promotions_coupon_user.status` | `UNUSED → LOCKED → USED`，`LOCKED → UNUSED`（取消/关单），`UNUSED → EXPIRED`（回收） | 下单事务内 CAS 锁；`PayService` 结清后核销；`OrderFlowService` 取消回退；`CouponExpireJob` 扫 `expire_time<=now` |
| 满减满折活动 | `non-reviewed → reviewed ⇄ unreviewed`，`status` 布尔另作"是否启用" | MK-A6；只有 `reviewed && status && 时间在窗` 参与算价 |
| 秒杀场次 | `pending → doing → end`（按时间派生，不落列）+ `disabled` 停用 | SK-A5 |
| 秒杀库存 | `available = activity_store − sales_store − hold_store` | 下单 CAS 占 `hold_store`，结清 `hold→sales`，取消/超时释放 `hold`；`auto_cancel_time = min(now+validity_period×60, activity_end_time)` |
| 拼团 | `team_status 1 进行中 → 2 成功 / 3 失败` | 参团 CAS `join_person_num<person_num`；结清后计数满员即成团；`GroupsSettleJob` 到期失败→**复用 M1-1 退款链**自动全额退 + 释放库存与名额；临近结束（≤600s）且 `robot=true` 补 `user_id=0` 机器人成团 |
| 拼团单发货 | 未成团（`team_status!=2`）不得发货 → 422 | `OrderAdminController` 发货前置校验；订单状态本身**不新增**（钱进来就是 `PAYED`） |

定时任务沿用 M0-6 的口径（`@Scheduled` + 条件更新代替分布式锁，测试可直接调 `job.runNow()`）：`CouponExpireJob`、`GroupsSettleJob`、`SeckillSettleJob`（释放超时未付的 `hold_store`，与订单超时关单同一扫描节拍、不重复扣）。

### 14.7 与源系统的差异（D 系列续编，追加于 §13.3 之后）

| # | 变更 | 理由/缓解 |
|---|---|---|
| D22 | 券只落统一卡券表，不做 `kaquan_*` 全字段 | 源系统券有 12 类字段（导购券/兑换券/使用平台/转赠/`most_cost` 封顶…），本项目 `promotions_coupon(_user)` 只保留算价与生命周期必需列。**`coupon_type` 在 seed 里那张"满100减20"取的是 `discount`**，与 `cash` 同语义（都按 `discount_fee` 分抵扣）；按比例折扣券、兑换券属 P2，读到未知类型不产生优惠 |
| D23 | 补齐"下单锁券"，源系统其实没有 | PHP `CountPreferentialFee` 调 `freezeUserCard()`，而该方法**全仓无实现** — 券没有锁定态，一张券可以同时挂在多笔待付款单上。本项目引入 `LOCKED` 态并走 CAS（`WHERE status='UNUSED'`），待付款单占用期间该券不可复用；取消/关单必回退，回退也走 CAS（`WHERE status='LOCKED' AND order_id=?`） |
| D24 | 秒杀库存从 Redis 池改为 DB 三列 CAS | 源系统 `SeckillActivityItemStoreService` + Redis Lua + 延迟任务 `CancelSeckillPlatTicket`；本仓库无 Redis。改成 `activity_store/sales_store/hold_store` 三列，可购量 = 总 − 已售 − 占用，占用与释放都是条件更新（受影响行数 0 即失败），与 M0-6 商品库存同一套语义。Redis 的"占资格 ticket"由 `validity_period` + 超时关单代替 |
| D25 | 拼团超员在结构上不可能发生 | 源系统 `updateNum()` 是 `findOneBy` + `+1` 的读—改—写（事务内但无 CAS），并发两笔能把 2 人团坐成 3 人，靠"已成团再来就立刻退款"兜底。本项目参团走 `WHERE team_status=1 AND join_person_num<person_num` 条件更新，抢不到名额直接 422；成团判定同样 CAS，只有一笔能把它推到 2 |
| D26 | 不新增订单状态表达"待成团" | 源系统有 `WAIT_GROUPS_SUCCESS` 订单状态。本项目"未成团"是**团队状态**不是订单状态：付款成功即 `PAYED`，发货侧加"未成团不得发货"卡口，失败团由 `GroupsSettleJob` 走 M1-1 退款链全额退。少一个状态就少一处状态机漂移，且不影响 M0-6/M1-1 已交付的迁移白名单 |
| D27 | 满减档位基数 = 会员折后应付 | 源系统 `OrderService` 按原价匹配档位、且会员折扣排在券之后，而同一份代码里交易级 `DiscountService::discount()` 是会员卡折扣先于券 — PHP 自身两处不一致。本项目固定为「等级折让 → 满减 → 券 → 积分」，docs/03 §7.1 的"会员折扣"位置留给 P2 的人群定向折扣；等级折让的逐行 floor 口径（C22）保持 M0-5 交付时一字不动 |
| D28 | 券与积分抵扣逐行分摊 | 源系统只在单级记 `coupon_discount/point_fee`，行不分摊；本项目要求 `Σ行=组=单` 的守恒式（§14.5），否则 M1-1 的按件分摊会把未分摊的抵扣重复退一次。`orders_normal_items.coupon_discount/point_fee` 两列本来就为这个口径预留 |
| D29 | 团号用号段，不用 PHP 的 16 位拼串 | PHP `GetTeamIdTrait::genId()` = 距 2012 的天数 + 90 秒槽 + Redis 计数器 + `user_id%10000`，依赖 Redis 且同秒并发靠运气。本项目 `team_id = "GT" + SeqGenerator(group_team_id)`，唯一索引 `uk_team` 兜底，可重复生成、可测 |
| D30 | PHP 的 everyMinute 闭包与队列 job → 三个显式 Job 类 | 源系统调度写在 `EspierBundle/Console/Kernel.php`，本地不可复现也不便测试。本项目 `CouponExpireJob/GroupsSettleJob/SeckillSettleJob` 各暴露 `runNow(now)`，端到端用例直接驱动时间而不等调度 |

## 15. 实现期语义补充（M1-3 #55 优惠券 + #56 满减满折挂进算价链，追加于 §14 之后，不回改上文）

本节记的是"实现之后才确定"的口径；§14 的规格原文一字不动，冲突处以本节为准（与 §5..§13 同一惯例）。

### 15.1 口径（C 系列续编，追加于 §13.2 之后）

| # | 口径 | 说明 |
|---|---|---|
| C109 | ORDER-1 的积分开关请求名回到 `point_use` | §2.4 公共入参与 ORDER-2 一直是 `point_use`，`PreviewForm` 原先声明成 `use_point`——M0 把它当纯开关位，没人真传过，所以字段名漂移一直潜伏着；券/积分开始真抵钱后第一次暴露就是**预览不抵、下单却抵**（预览≠下单）。现按文档名收，`@JsonAlias("use_point")` 兼容旧写法。教训：凡"M0 接受但不产生金额"的字段，M1 落地时必须回头核对**请求侧键名 == 响应侧键名 == 兄弟端点键名**。 |
| C110 | ORDER-2 显式给券码而抵扣为 0 → 422 | 撤销 C25 的开关位口径。券码给了但门槛没到 / 多店铺不抵时，下单直接拒绝并回**与 ORDER-1 同一个 `coupon_reason`**（复用 `CartService.couponReason`），绝不静默按"不用券"成交——否则用户看到的价格与成交价不同，还把券吞了。占用逻辑同口径：只有真扣了钱才 `UNUSED→LOCKED`，抵扣为 0 时不动券。 |
| C111 | 一店一活动择优 | 同一店铺组内多个满减/满折**只记折让最大的一个**（同额按 `marketing_id` 升序定序），`discount_info[]` 与 `used_activity[]` 都只出一条。择优发生在算价而不是取数：库里可以同时是 `reviewed`，取数只筛「审核 + 启用 + 在窗」。 |
| C112 | 满减基数不含加价购行 | `is_plus_buy` 的行不参与门槛与折让（它本身就是换购价），否则"满 100 减 20"会把 10 元换购品也算进门槛、再减一次。 |
| C113 | `free_postage` 只写标记，运费仍由 FreightRule 判 | 活动命中只把组标记 `g.freePostage` 置真；`FreightRule` 的判序是「活动包邮 → 模板包邮 → 满额包邮 → 按计价量收费」。未命中的组不受影响（同模板的另一件照收运费），包邮也不参与抵扣（抵扣在前、运费在后，§14.5 的顺序）。 |
| C114 | 元↔分只在 `MarketingRuleParser` 换算一次 | `condition_value` 入库保持 PHP 的**元字符串**（存量行不改写），出参 `steps[]` 给分 + 文案。脏数据分级：整串非法 JSON → `steps=[]`（活动视为未配置，不减钱也不报错）；单档脏（`full<=0`、金额型 `minus>full`、`discount` 不在 1..<100）丢该档；新建/编辑时一档都不合法 → 配置台 400「至少要有一档合法规则」，而**存量**脏行仍只判"不优惠"（不能因为一条脏历史把结算链打死）。 |
| C115 | 审核与启用是两个维度 | MK-A6 只改 `check_status`；「审核通过但停用」这一态由 `status` 列表达（审核 API 不碰 `status`，用例只能配 SQL 出来）。配置台默认列表**不按 `status` 过滤**（运营要能看见并重新启用停用的活动，`?status=false` 才是筛停用的视图），前台与算价一律三筛 `reviewed && status && 在窗`——筛子与取数同一条 SQL 语义，所以"页面写着满 100 减 20、结算时不减"不会发生。 |
| C116 | 行上活动痕迹只在 CART-2 | ORDER-1 的 `items[]` 是 PHP 的价格行（只有金额字段），`activity_info[]` 挂在 CART-2 的 `valid_cart[].list[]` 上。前端要显示"这一行的便宜是谁给的"读购物车面，不要读预览。 |
| C117 | `limit_point`/`max_point` 是"本单可用"口径 | 未勾 `point_use` 时两者都回 0（比例位不显示，前端据此隐藏），勾了才回 `max_ratio` 与 `min(余额, 折让后应付 × 比例%)`；`user_point` 始终是账上余额。三个字段各说一件事，前端只有一个判据。1 积分 = 1 分（C52 的 `PointRate` 不变）。 |
| C118 | 券的三处实现期口径 | `get_limit` 是**每会员累计限领**（不是每单）；CP-F3 默认桶不含 `LOCKED`（在途占用的券不单列，要看 `?status=LOCKED`）；`listAvailable` 故意不过滤已到期的 `UNUSED` 行——用户要能在结算页看到"我的券为什么不能用"，回收由 `CouponExpireJob` 负责而不是查询负责。 |
| C119 | seed 那张"满100减20"的 `coupon_type='discount'` 按现金券抵 | 与 D22 同源：`cash`/`discount` 在本项目同语义（都读 `discount_fee` 分）。真正的比例折扣券、兑换券属 P2；读到未知类型**不产生优惠**而不是报错。 |
| C120 | 关单回补抵现积分只回余额 | 只 `point += ?` 并记 `point_member_log`（`remark=订单关闭，积分抵现回补`），**不动 `total_point`**——回补不是"获得积分"，累计列一动等级与榜单就漂（与 M1-1 退积分同一口径）。账户行不存在时静默跳过：那是数据异常，不该让一次取消失败。 |
| C121 | 优惠快照一行一笔折让，取消只作废不删行 | `orders_rel_promotions` 按「活动/券/积分各一行」落，`Σdiscount_fee == item_fee − total_fee`（不含运费）；关单把 `status` 置 `invalid`，行留着。退款与对账读快照不重算——活动模板与券模板随时会被改被删。 |
| C122 | 权限码 seed 实落 27..37 | §14.2 的"27..40"是按端点位预留的编号段。#55/#56 落库的是 `promotions`(catalog) + `promotions.coupon.lists/update/send` + `promotions.marketing.lists/update/check` = 27..37；`seckill/groups` 的四条码随 #57 一起追加。**端点先有码再有路径**，编号段允许比文档窄。 |

### 15.2 与源系统的差异续编（D 系列，追加于 §14.7 之后）

| # | 变更 | 理由/缓解 |
|---|---|---|
| D31 | `use_bound` 只支持 0 全场 / 1 指定商品 | 源系统另有 2 按分类圈品，`use_shop`/`shop_ids`（多店铺定向）也未接。本项目对 `use_bound=2` 直接 400「只支持 0 全场 / 1 指定商品（按分类属 P2）」，并把列原样留着——存量行读得到，只是不参与算价。 |
| D32 | 满折也记折让额，不记折率 | `full_discount` 的 `discount=85` 在解析时立刻折算成 `基数 ×15/100 向下取整`，落库与快照都走 `discount_fee` 一列（源系统同字段），`activity_type` 用 `full_minus`/`full_discount` 区分来源，不出第二列。 |
| D33 | 圈品是全量覆盖，`errors` 键带下标 | MK-A5 一次 `PUT` 就是最终态（源系统先清后插但校验只到"整体非空"）。本项目逐行校验并把错误定位到 `items[0].item_id` / `items[0].price` 形态的键，后台表单能直接标红到行；空圈、重复 `item_id`、`id<=0`、负价都是 400。 |

### 15.3 实跑冒烟

`tools/m13_smoke.py`（#56：满减满折 + 券/积分挂链，53 项）设计成**可重复跑**：进场先按 `MK<时间戳>` 名字预清理上一轮的活动（半途中断的残档会污染收尾那条"全场折让回 0"），积分与待付款残单都按**基线差值**断言，本案建的活动全部 MK-A7 删除、券按 CP-A6 停发（已发放过的券删不掉，422 是正确行为）。注意 m07 与 m04 仍要求干净库（`user_deposit`、`items_rel_attributes` 的绝对值断言），这是它们 docstring 里写明的既有约束，不是本轮回归。

## 16. 实现期语义补充（M1-3 #57 秒杀与拼团落地时确认，追加于 §15 之后，不回改上文）

同上惯例：§14 的规格原文一字不动，实现之后才确定的口径记在这里，冲突处以本节为准。

### 16.1 口径（C 系列续编，追加于 §15.1 之后）

| # | 口径 | 说明 |
|---|---|---|
| C123 | 秒杀与拼团的前台列表**筛子不一样**，且都不是"只出在窗" | SK-F1 `frontActivities` 只滤 `disabled=false`，未开始与已结束照出——秒杀页的三个 tab 就靠行上的 `date_status` 分（`pending`/`doing`/`end`），把 `end` 滤掉 tab 页就没数据了。GP-F1 缺省 `dateStatus="doing"`（拼团列表没有 tab）。**冒烟与前端都不能假设"前台列表 = 在窗活动"**：本案实测前台第一条就是 seed 那条已结束的场次 1。停用位两侧都不出。 |
| C124 | `report.available_store` 是**全场次求和**，不是某一行的可购量 | SK-A7 的 `available_store = Σ max(activity_store − sales_store − hold_store, 0)`，行级数字只在 `items[].available`。后台报表看总量、SKU 看行，冒烟脚本第 5 节实测 `available_store=15`（坚果 13 + 手机 2）而单看坚果是 13，断言写错对象会一路红。同理 `sales_fee/hold_fee` 也是单级求和。 |
| C125 | `saveGoods` 是整表覆盖 + 库存只加，返回的是**全部行** | 提交的行覆盖（`item_id` 相同即 UPDATE），未提交的行原样留着（不是差量删除）；`activity_store` 入参按**增量**解释（只允许加），负增量 422「只能加不能减」，空圈品 400 表单错；出参 `items[]` 是该场次的全量行，改一个 SKU 也会看到另一行——冒烟据此断言"没提交的那行原样留着"。 |
| C126 | 秒杀类型用**列值 + 整数码双写**，未知码不再静默降级 | 列 `seckill_type` 存 `normal`/`limited_time_sale`，出参同时给源系统的 `activity_type`（1/2）与 `seckill_type_text`。配置台发整数码：只认 1/2，其余（如 7）现在报 400「只支持 normal/limited_time_sale」而不是悄悄建成普通秒杀。`limited_time_sale` 17 个字符，建表时的 `VARCHAR(16)` 装不下 → schema 末尾追加放宽到 `VARCHAR(32)`（H2 与 MySQL 同一语句可跑）。 |
| C127 | 重放的是**渠道回调**，不是再调一次 PAY-2 | `PAY-2` 对已付单回 422「订单已支付」是正确行为，冒烟要验"结清不双计"得往 `/api/notify/wxpay` 再发一条同 `out_trade_no` 的报文：应答仍是 `SUCCESS`（渠道要 ack），而 `hold_store→sales_store` 那条 CAS 第二次拿 0 行、销量与成交额一动不动。 |
| C128 | 失败团**保留已付成员行**，只改团状态 | 判团失败把 `team_status` 置 3、未付成员删行回补额度，但**已付成员行留着**做对账与退款依据；机器人行 `user_id<0` 不进退款名单。`GroupsSettleJob` 再跑一轮既不复活也不重复退钱（e2e 第 20 例与实跑第 10 节同口径）。 |
| C129 | 拼团的两条限购判据分开，且"一件一席位" | ①「同一活动只允许一笔待成团单」只数**进行中团里的未付成员行**（`countUnpaidInAct`），失败团与已成团不算；②`limit_buy_num` 数的是"失败团以外"的参团次数（已付 + 在途都算，`countJoinedInAct`），堵住反复下单占位；③拼团单一次只能拍 1 件（`num>1` 直接 422），因为一个席位对应一件货。 |
| C130 | 「未成团不得发货」是发货卡口，不是订单状态 | D26 的落点：结清即 `PAYED`，`canShip` 只在 `team_status=2` 放行，否则 422「拼团尚未成团」。后台 `end=true` 只砸进行中的团（`transit(1→3)` 的 CAS），已成团的团与单都不动。 |
| C131 | 机器人凑团的窗口是**提前量**，不是绝对时刻 | `listNearEndForRobot(now, horizon)` 的判据是 `now < end_time <= now + horizon`（源系统 600 秒）。本轮实测把 `horizon` 当绝对时刻写进 SQL（`end_time <= #{horizon}`），结果 `runNow()` 永远返回 0——机器人一次也没补过团。机器人占席位（`join_person_num`/`robot_num += lack`）但**不占活动额度**，所以"额度 = 满额 − 仍被占住的席位"这条不变式只对 `robot=false` 的活动成立。 |
| C132 | ORDER-5 详情补 `team_id` | 参团页与「我的团」都要从订单详情跳回团，`OrdersMapper` 的列清单里早有这一列（D29 落主表），只是出参没装配——冒烟脚本 `KeyError: 'team_id'` 暴露。非拼团单回 `null`（`default-property-inclusion: always` 保证键位在）。契约字段集同步进 `OrderContract.ORDER_INFO`。 |

### 16.2 与源系统的差异续编（D 系列，追加于 §15.2 之后）

| # | 变更 | 理由/缓解 |
|---|---|---|
| D34 | 拼团额度与商品库存同一套 CAS 语义 | `promotion_groups_activity.store` 走 `deductStore`（`WHERE store >= #{num}`）/`restoreStore` 两条条件更新，取消、超时关单、成团失败三处都只按**已占件数**回补。源系统在团失败里直接改商品表，回补路径没有守卫，并发下能把额度加回去两次。 |
| D35 | 团行不快照"成团人数" | `joinSeat(teamId, personNum)` 的人数由调用方读活动后传入，改活动人数即时作用于新加入者。源系统把 `person_num` 抄进团行，改配置还得回刷历史团，漏刷就出现"团里人数上限与活动不一致"。 |
| D36 | 失败团退款复用 M1-1 链路 | 结算侧不另造退款通道：`AftersalesService.apply(ONLY_REFUND, autoApprove=true)` + `RefundService.checkRefund(checkRefund=1)`，全程走 M1-1 的闸门与幂等，订单 `pay_status` 最终 `REFUND`。因"仅退款不补库存"是 M1-1 的既定口径，拼团失败是系统原因，所以结算侧**显式**回补 `items.store` 与活动 `store`。退款失败只记日志不回滚团状态——团已判死，钱由售后面的重试与对账兜。 |
| D37 | 机器人成员用负数 `user_id` | 源系统用 0，而本项目 `(team_id, user_id)` 唯一索引（D25 的一部分）下 0 只能坐一行，凑不齐 `person_num-1` 个空位。改成 `-(team.id*100 + 序号)`，出参按 `user_id < 0` 报"虚拟成员"，昵称固定"虚拟成团"。 |
| D38 | 秒杀资格票 = `auto_cancel_time` | D24 的落地细节：建单时写 `min(now + validity_period*60, 活动结束)`，超时关单顺带释放 `hold_store`；`SeckillSettleJob` 只处理"单已不在 `NOTPAY` 而 `hold_store` 仍占着"的悬空占用（`listOrphanHold` 逐行比对订单态），Redis 票池与延迟任务都不需要。 |

### 16.3 实跑冒烟

`tools/m13_skgrp_smoke.py`（#57：秒杀 SK-A1..A7 / SK-F1..F2 + 拼团 GP-A1..A6 / GP-F1..F4 + 活动单算价，**69 项**）同样设计成可重复跑：进场按 `SG<时间戳>` 前缀停用上一轮残留的场次与活动（两侧都没有删除口，停用即归档），待付款残单按**基线差值**断言，收口把本案两个场次全部停用并断言"算价链回到 seed 态（全场折让 0）"。同库连跑两遍 69/69 全绿；清库后九脚本顺序回归见 docs/05 的 #57 验收段。

本轮顺手改了一处邻居脚本的不变式：`tools/m07_smoke.py` 的收尾原来断言"会员待付款单必须为 0"，而常驻演示库里 m11/m12 各自刻意留着在途单（与 §15.3、D21 同一族的"全局归零式断言只能单跑成立"），改成进场记基线、退场回到基线。**余额不足**那类绝对值断言仍按 m07 docstring 的干净库口径，不在本轮放宽。

## 17. 实现期语义补充（M1-3 #58 优惠叠加矩阵与并发门槛落地时确认，追加于 §16 之后，不回改上文）

同上惯例：§14 的规格原文与 §15/§16 的既有结论一字不动，本轮把五档开关全组合跑穿之后才定下来的口径记在这里，冲突处以本节为准。

### 17.1 口径（C 系列续编，追加于 §16.1 之后）

| # | 口径 | 说明 |
|---|---|---|
| C133 | 券的门槛基数**确定为「满减之后、积分之前」的组内应付**，且这个结论是可证的不是举例 | §14.5 把链路顺序写成 15→20→30→40→50，但"券到底比哪个数"此前只有单点用例。本轮用 `秒杀活动价 × 会员等级折 × 满减 × 优惠券 × 积分抵现` **五档 16 格**（e2e）与**两套等级 × 16 格**（实跑 32 格）逐格反推：`行小计(活动价后) → 减会员折 → 满减门槛 → 券门槛 → 积分上限` 一层比一层小，专门造了 `17700 − 885 − 3000 = 13815` 这一格卡在"满 140 元"门外——同一笔行、同一张券，**只关掉满减**这一个开关就立刻能用（16815 ≥ 14000）。链路顺序一旦被改动（例如把会员折挪到满减之后），必然有几格对不上，这比单点用例更难"改个数字继续绿"。 |
| C134 | 拒券文案由**算价当场记因**（`PriceContext.couponReject`），不再事后重算门槛 | 门槛判定发生在 order 40（满减之后、积分之前），而 `couponReason` 原先在整条链跑完之后重算，那个数已经被积分又减掉一截：实测把用户够得着的 138.15 元报成"当前 69.08 元"——**判据没错、文案骗人**，而且只在"券 + 积分同时在场"这一格出现。现在 `CouponRule` 判定失败时把原因当场写进 `ctx.couponReject`，`CartService.couponReason` 优先读它，事后重算只留给规则没跑到的边角（多店铺、无应付、券实例没取到），并把兜底基数加回 `couponDiscount + pointFee`。ORDER-1 与 ORDER-2 读同一个字段，预览与下单不会两条文案。 |
| C135 | ORDER-1 的行投影**没有** `grade_name`，等级名与等级折长在 CART-2 的 `lineView` 上 | 矩阵造数时按 ORDER-1 的 `items[0].grade_name` 断言"金卡会员"直接拿到空串。`grade_name`/`member_discount` 是结算行（CART-2 `valid_cart[].list[]`）的字段，预览的行只给金额三件套（`price/origin_price/item_fee/…`）——与 C116 同源的一条边界：**行上的活动痕迹与等级痕迹都只在 CART-2**，ORDER-1 只管钱。前端要显示"你是金卡、这一行省了多少等级折"读购物车面。 |
| C136 | 秒杀/拼团名额不足有**两条拒绝路径**，文案不同且都正确 | ①串行进单走 `OrderCreateService` 的**预检**（`l.num > o.available`）报「`{商品名} 活动名额不足」；②临界并发穿过预检后走 CAS（`SeckillPool.hold` / `acts.deductStore` / `teams.joinSeat`）返回 0 行，报「秒杀商品已被抢完」「拼团名额已用完」「该团名额已满，请重新开团」。冒烟与用例断言"被拒的那笔"时**不能只认一条文案**（本轮两处断言因此从精确文案放宽为 `名额|抢完` 的子串判据）；预检只让正常路径的报错更快更准，真正守住池子与席位的是 CAS。 |
| C137 | ORDER-5 的 `discount_info[]` 只有**行级两档**（活动价 + 满减），且此前在任何单据上恒为 `[]` | 本列由订单行的 `discount_info` 聚合而来，存的是**对象数组**；装配用的 helper 是 `jsonList`（为 `pics` 这类字符串数组设计，元素回 `String`），`mapNotNull { it as? Map }` 于是永远是空集。实测一张满减单 `discount_fee=3000` 而 `discount_info=[]`，折让构成从 HTTP 面整个消失——只有实跑冒烟会撞见（e2e 此前不断言这个字段）。新增 `jsonMaps` 按对象数组解析（非数组或单元素解析失败都回空集，脏行不许把整张详情带崩）。券与积分**不进这个数组**：它们是组级/单级账位，单级另由 `coupon_discount`/`point_fee` 表达（与 C116、§14.5 的"行上只带活动痕迹"同一条边界）。 |
| C138 | GP-F4（我的团 / 团列表）的载荷键是 **`teams`**，不是 `list` | `GroupsService` 两处出参与 `GroupsFrontController` 一律 `{"teams": […]}`。写冒烟照 §14 的直觉取 `data["list"]` 直接 `KeyError`；`SK-A7` 报表同理是 `items[]` + 单级三个求和列（C124）。 |
| C139 | 抵扣越界一律**钳到应付**，抵光后 `point_fee` 与 `max_point` 双双归零 | 「无门槛减 300」压在 13815 分的行上：`coupon_discount == 13815`、`total_fee == 0`、积分一分不动、上限位跟着归零、`discount_info[]` 里也就**不出现 `point` 一条**（`type` 序列只记真抵到钱的活动）。判据在 `CouponCheck.deductOf = min(面额, 应付)` 与 `spreadToLines` 的单行钳制，`clampLine` 的回收顺序仍是 积分 → 券 → 活动。矩阵每一格都断"行应付不为负"（D28 的守恒式在此变成可跑的）。 |
| C140 | 四个账各自独立，矩阵逐格断言的正是这四个数 | `discount_fee == 会员折 + 满减`、`goods_discount == 满减`、`member_discount == 会员折`、`activity_price_fee == 活动让利`（**不进 `discount_fee`**，D24），券与积分各记各的。`Σ行 total_fee + 运费 == 单 total_fee`、三类抵扣逐行守恒、`discount_info[]` 的 `type` 序列 == 链路顺序且金额合计 == 四类让利之和——四条账任一条被并起来（例如把活动让利塞进 `discount_fee`）都会立刻有几格红。 |

### 17.2 与源系统的差异续编（D 系列，追加于 §16.2 之后）

| # | 变更 | 理由/缓解 |
|---|---|---|
| D39 | 叠加矩阵的期望值由**独立算术模型**给出，不复用责任链 | `DiscountOverlayMatrixTest.oracle()` 与 `tools/m13_matrix_smoke.py` 的 `oracle()` 都照 §14.5 的"逐级基数"手写一遍乘除与 `min`，被测代码只出数。若拿链路的输出当期望，测试就只能证明"代码等于它自己"——本轮 C134（判据对、文案错）与 C137（字段恒空）两个真 bug 都是这种"自证式断言"照不出来的。 |
| D40 | 会员等级没有 HTTP 写口，矩阵靠**两个会员 + 一条 SQL** 切档 | 本项目只有 `members.grade_id`，改等级属后台会员编辑（未在 P1 端点位里）。e2e 用 `UPDATE members SET grade_id = 3` 现场切金卡并断言 CART-2 行的 `grade_name` 跟着动（C135 的教训就踩在这一步）；实跑脚本不能连库，改用**两个会员各持一张券**（普通会员 / 金卡，积分余额 1000 与 500）分别登录，每格读实时余额，等级与余额都不必写死。**积分余额决定哪条钳制分支被压到**：矩阵每格都由比例钳（e2e 把余额给到 90000），余额钳那条路留给 #56 的 Order 9 单独验。 |
| D41 | 满减活动没有"只改开关"的端点，矩阵用**建 + 审 / 删**两次调用切状态 | 编辑必打回重审（§15.1 C115 的审核闸门），所以"满减关"这一格只能 `DELETE`、"满减开"这一格只能重新 `POST` + `PUT /check`。这也是矩阵每格必须**自己开关**、并在进场先复位的原因——上一条用例漏删的残档会污染后面 15 格的基数。 |

### 17.3 实跑冒烟

`tools/m13_matrix_smoke.py`（#58：五档开关全组合 + 四叠加下单落库 + 并发超员/抢额度/抢池，**68 项**）与两个 e2e 门槛用例分工明确：用例管**口径**（逐级基数、四账独立、守恒式、`discount_info` 序列），脚本管**起得来的服务**（真 HTTP 面、真令牌、真 CAS）。脚本设计成可重复跑，与 §15.3/§16.3 同一套纪律：进场按 `MT<时间戳>` 停用上一轮残档、待付款残单与积分都按**基线差值**断言、收口把本案活动全部停用/删除并断言"算价链回到 seed 态（全场折让 0、行价回现价）"。并发三段用 `ThreadPoolExecutor` + `threading.Barrier` 同瞬起跑：8 路挤 2 人团恰好 1 单成功、9 路抢额度 3 恰好成 3 团、9 路抢池 3 件恰好 3 单（`hold=3 / sales=0 / available=0`），取消后席位、额度、池子三者全部回位。等级与余额两处不能写死的口径见 D40。

## 18. P1 端点规格 —— 店铺与分销域（M1-4 开发前补齐，追加于 §17 之后，不回改上文）

范围按 docs/05 §M1-4：**分销商（店铺）CRUD + 审核、店铺商品双模（价格/库存/上下架）、导购 salesman + 员工端链、佣金 `distribute_logs`（入账/冲销/结算）、提现申请-审核-打款、自提点 + `ziti_code` 核销**。
不做（P2）：店铺标签/分类、开屏广告与轮播、地理围栏与配送距离、自配送配置、店铺白名单、进店规格（inRule）、小程序码、企微同步、导购任务/通知/排行榜/赠券、社区团购团长域、推广员(promoter)域、分账(渠道侧)、statements 账期对账单（§3 已整域划 P2）、快证/旺店通店铺映射。

### 18.0 盘点结论：源系统落位与本项目落位

| 本项目表（`sql/schema.sql` 已存在） | PHP 落位 | 现状 |
|---|---|---|
| `distribution_distributor` | `Distributor`（DistributionBundle） | seed 两行（1 商城总部 `is_distributor=TRUE` 自营/平台收款；2 demo 旗舰店 加盟/店铺收款），**Kotlin 侧只有 `DistributorShopMapper.listByIds` 一条只读口**（购物车与订单详情取店铺名/Logo） |
| `distribution_distributor_items` | `DistributorItems` | 零引用。列里 `store/price/is_show/is_can_sale/is_total_store/sales/default_item_id` 就是双模的全部依据 |
| `distribution_distributor_salesman` | `Salesperson` | 零引用；`Principal.Kind.SALESMAN` 已定义但过滤器链不认（`SecurityConfig` 注释明写"员工端链在 M1-4 补"） |
| `distribution_distribute_logs` | `DistributeLogs` | 零引用；`OrderCreateService` 已把行佣金算进 `orders_normal_items.rebate/total_rebate`，`RefundService` 已调 `reverseCommission` 只减订单列、不写日志 |
| `distribution_cash_withdrawal` | `CashWithdrawal` | 零引用 |
| `distribution_pickup_location` | `PickupLocation` | 零引用；`orders_normal_orders.receipt_type/ziti_code/ziti_status` 三列已建，`ziti_code` **建单时从不生成**、`ziti_status` 从不写 |

订单侧已有的挂接点（本轮只补语义，不动列）：`distributor_id`（= 建单时的 `shop_id`）、`salesman_id`（ORDER-2 已接收，落单不改归属）、`order_holder`、`is_distribution`、明细 `is_total_store`（当前**硬编码 true**）、`items.distributor_id`（0=商城总部）。后台 `OrderAdminController` 的"distributor_id 越权过滤属 M1-4"注释与 `ItemQuery` 已有的 distributor 三态筛选是本轮要落的两处。

### 18.1 会员面（前缀 `/api/h5app/wxapp`，鉴权=会员 JWT，链=frontChain；标 * 的走匿名）

| # | 方法 | URL | 说明 |
|---|---|---|---|
| DS-F1 | GET* | `/distributors` | 营业中店铺列表：`distributor_id,name,logo,banner,is_valid,distribution_type,is_ziti,is_delivery,province,city,distance?`（分页 + `keyword`/`city` 过滤） |
| DS-F2 | GET* | `/distributors/{distributor_id}` | 店铺主页：档案 + `introduce` + `pickup_locations[]`（该店启用的自提点） |
| SI-F1 | GET* | `/goods/shopitems` | 店铺商品列表：入参 `distributor_id`(Y) + 与 GOODS-7 同的筛选/分页；出参行 = GOODS-7 字段集 + `shop_price`/`shop_store`/`is_show`/`is_can_sale`/`is_total_store`（**价与库存给店铺态**，未挂品的行不出现在列表里） |
| SI-F2 | GET* | `/goods/items_price_store/{item_id}` | 单行价格与库存：`?distributor_id=` 缺省=总部态；出参 `{item_id,price,store,is_show,is_can_sale,is_total_store}` |
| PL-F1 | GET* | `/distributor/pickuplocation` | 可用自提点：入参 `distributor_id`(Y)、可选 `lng/lat`；出参 `{list[]{id,name,province,city,area,address,contract_phone(掩码),distance}}`，只出 `status=1` 且归属该店（或 `rel_distributor_id=0` 全平台通用）的点 |
| CW-F1 | POST | `/cash_withdrawal` | 店铺提现申请：`{distributor_id(Y), money(Y,分)}` → 出参 `{id,status:'applied',money}`。闸门见 §18.5 |
| CW-F2 | GET | `/cash_withdrawals` | 我的提现记录（店铺视角）：分页 + `status` 过滤；出参行见 §18.5 |
| CW-F3 | POST | `/salesman/applyCashWithdrawal` | 导购提现申请：`{money}`，归属取**当前会员绑定的导购行**（`user_id` 命中 `salesman`），非导购 403 |
| CW-F4 | GET | `/salesman/getCashWithdrawalList` | 导购提现记录 |
| SM-F1 | GET* | `/salesperson` | 导购信息（扫码进入）：`?salesman_id=` 或 `?mobile=`，出 `{salesman_id,salesman_name,avatar,distributor_id,shop_name,qrcode_text}`；查不到回 404 |
| SM-F2 | POST | `/member/bindSalesperson` | 绑定我的导购：`{salesman_id(Y)}`，写 `members.salesman_id`（**schema 末尾追加该列** `ALTER TABLE members ADD COLUMN IF NOT EXISTS salesman_id BIGINT NOT NULL DEFAULT 0`，源系统另有关系表带"24h 有效绑定"语义，本项目只留当前归属、可换绑），出 `{user_id,salesman_id,bind_time}` |
| ZT-F1 | GET | `/order/{order_id}/ziticode` | 自提取货码：出 `{order_id,ziti_code,ziti_status,pickup{name,address,contract_phone},valid_text}`；只有本人 `receipt_type=ziti` 的单可查 |

### 18.2 后台（前缀 `/api`，鉴权=运营 JWT + `@RequiresResource`，写路径 `@Audited`）

| # | 方法 | URL | 说明 / 权限码 |
|---|---|---|---|
| DS-A1 | POST | `/distributor` | 创建店铺：`name(Y) mobile(Y) province city area address logo banner introduce distribution_type(0自营/1加盟) is_ziti is_delivery is_self_delivery payment_subject rate shop_id`。`source_from=1`、`review_status` 由入参决定（缺省 true）；自营店全场唯一（已有 `is_distributor=TRUE` 时再建自营 → 422）。`distributor.lists`→`distributor.update` |
| DS-A2 | GET | `/distributors` | 列表：`keyword`/`is_valid(true|false|closed)`/`review_status`/`distribution_type`/`source_from` + 分页；行含 `order_count`、`commission_fee`（未结算佣金）与 `balance`（可提现） |
| DS-A3 | GET | `/distributors/info` | 详情：`?distributor_id=`，出档案全字段 + 审核态 + 收款主体 + 费率文案 + 统计三数 |
| DS-A4 | PUT | `/distributor/{distributor_id}` | 更新（**不碰 `review_status`/`is_distributor`/`company_id`**；自营店名称与联系方式可改，`distribution_type` 建后不可改 → 422） |
| DS-A5 | PUT | `/distributor/{distributor_id}/review` | 审核：`{review_status(Y: true/false), reason?}`；`source_from=1` 且已审过的重复审核 422「无需重复审核」 |
| DS-A6 | PUT | `/distributor/{distributor_id}/status` | 运营开关：`{is_valid: 'true'|'false'|'closed'}`；`closed`（关店）要求**无在途提现且无未结佣金**，否则 422（先结后关） |
| DS-A7 | PUT | `/distributor/{distributor_id}/payment-subject` | 收款主体：`{payment_subject(0平台/1店铺), rate(万分比 0..10000)}`；改费率只影响**之后**的佣金，不回溯已落日志 |
| DS-A8 | GET | `/distributor/count/{distributor_id}` | 店铺统计：`{order_count,pay_fee,refund_fee,commission_total,commission_closed,commission_open,balance}` |
| SI-A1 | POST | `/distributor/items` | 挂品：`{distributor_id(Y), items:[{item_id(Y), price?, store?, is_total_store?, is_show?, is_can_sale?}]}`（**整表按 (店, 商品) upsert**，与 SK-A6 同一口径；`price` 不传=跟随总部现价） |
| SI-A2 | GET | `/distributor/items` | 店铺商品列表：`distributor_id`(Y) + `keyword`/`is_show`/`is_can_sale` + 分页；行给**店铺态与总部态两组**（`shop_price/price`、`shop_store/store`）便于运营对比 |
| SI-A3 | PUT | `/distributors/item` | 配置价格/库存/上下架：`{distributor_id(Y), item_id(Y), price?, store?, is_show?, is_can_sale?, is_total_store?}`；`store` 入参按**绝对值**（与 SK-A5 的增量口径不同，这里跟源系统"配置店铺库存"一致），`is_total_store` 由 false→true 时店铺库存列**保留但不参与扣减** |
| SI-A4 | DELETE | `/distributor/items` | 取消挂品：`{distributor_id(Y), item_ids[](Y)}`；只删关联行，不动 `items`，也不动订单历史快照 |
| SM-A1 | GET | `/salesmans` | 导购列表：`distributor_id`/`keyword`/`role_id`/`is_valid` + 分页；行 `{salesman_id,salesman_name,mobile(掩码),distributor_id,shop_id,role_id,role_name,user_id,is_valid,order_count,commission_fee}` |
| SM-A2 | POST | `/salesman` | 新增导购：`{distributor_id(Y), salesman_name(Y), mobile(Y), role_id?, user_id?, shop_id?, password?}`；`(distributor_id, mobile)` 店內唯一，重复 422 |
| SM-A3 | PUT | `/salesman/{salesman_id}` | 更新（姓名/手机号/所属店/user 绑定） |
| SM-A4 | PUT | `/salesman/role/{salesman_id}` | 调岗：`{role_id(Y)}`；角色必须是本店启用的角色 |
| SM-A5 | PUT | `/salesman/{salesman_id}/status` | 启停：`{is_valid: 'true'|'false'}`；停用即员工端登录被拒（**会话不吊销**，见 §18.3） |
| SM-A6 | GET/POST/PUT/DELETE | `/salesman/roles`、`/salesman/role/{role_id}` | 导购角色 CRUD：`{role_name(Y), remark?}`；被导购引用的角色不能删（422） |
| DL-A1 | GET | `/distribution/logs` | 佣金记录：`distributor_id`/`order_id`/`is_close`/`begin_time`/`end_time` + 分页；行 `{id,order_id,order_no,item_id,item_name,user_id,nickname(掩码),num,rebate,total_rebate,is_close,plan_close_time,created}` |
| DL-A2 | GET | `/distribution/count` | 分销统计：`{commission_total,commission_open,commission_closed,withdraw_applied,withdraw_paid,balance}`（全场或按 `distributor_id` 收窄） |
| DL-A3 | POST | `/distribution/logs/close` | 显式结算：`{distributor_id?, to_time?}` → 把 `is_close=FALSE AND plan_close_time<=to_time` 的行批量置已结算，出 `{closed_count, closed_fee}`；同一时间窗重复调用第二次 `closed_count=0`（幂等） |
| CW-A1 | GET | `/distribution/cash_withdrawals` | 提现列表：`status`/`distributor_id` + 分页 |
| CW-A2 | PUT | `/distribution/cash_withdrawal/{id}` | 处理：`{status: 'confirmed'|'refused'|'paid', remarks?}`；三态迁移见 §18.5 状态机 |
| CW-A3 | GET | `/distributor/cash_withdrawal/payinfo/{id}` | 打款信息（mock 回执）：`{id,money,status,pay_type:'offline',account{...},paid_time}`；非 `paid` 时 `paid_time=0` 且 `account` 给"待财务打款" |
| PL-A1 | GET | `/pickuplocation` | 自提点列表：`keyword`/`status`/`rel_distributor_id` + 分页 |
| PL-A2 | POST | `/pickuplocation` | 新建：`{name(Y), province, city, area, address(Y), contract_phone(Y), lng, lat, status(1/0), rel_distributor_id?}`（`rel_distributor_id=0` = 全平台通用点） |
| PL-A3 | PUT | `/pickuplocation/{id}` | 更新 |
| PL-A4 | DELETE | `/pickuplocation/{id}` | 删除（逻辑删 `status=0`；被未完结自提单引用时 422） |
| PL-A5 | PUT | `/pickuplocation/reldistributor` | 挂店/摘店：`{id(Y), distributor_id(Y), bind: true|false}` |
| WO-A1 | GET | `/writeoff/{order_id}` | 核销查询：`{order_id,order_no,ziti_code,ziti_status,receiver_name,receiver_mobile,total_fee,items[]}`；码不匹配 422 |
| WO-A2 | POST | `/writeoff/{order_id}` | 核销执行：`{ziti_code(Y)}` → 见 §18.6 |

### 18.3 员工端面（前缀 `/api/admin/wxapp`，**新增第三条链** `staffChain`）

源系统用 `provider=adminwxapp` + header `x-wxapp-session`。本项目保持**同一 header 名**（`x-wxapp-session`），值是本项目签发的 `Kind.SALESMAN` JWT；`JwtSalesmanFilter` 校验后把 `SalesmanPrincipal(salesmanId, distributorId, shopId, userId?)` 放进上下文。会员/运营令牌打到员工面是 **401**（`Unauthenticated.`），与既有的"跨面拒绝"同口径。

| # | 方法 | URL | 说明 |
|---|---|---|---|
| ST-E1 | POST | `/login` | 员工登录：`{mobile(Y), password(Y)}` → `{token, salesman_id, salesman_name, distributor_id, shop_id, role_id, role_name}`；密码走 `operators` 同一 bcrypt 列（`salesman.password`，schema 末尾追加）；停用/未设密码 401 |
| ST-E2 | GET | `/getinfo` | 当前员工信息（含所属店铺名与角色名） |
| ST-E3 | GET | `/shoplist` | 本店简易档（`distributor_id,name,mobile,is_ziti,is_delivery,hour`） |
| ST-E4 | GET | `/salespersonCount` | 我的统计：`{order_count,today_count,pending_count,commission_open,closed,balance}` |
| ST-E5 | GET | `/salespersonFee` | 我的佣金明细（分页，`is_close` 过滤），口径同 DL-A1 但**强制 `salesman_id=自己`** |
| SO-E1 | POST | `/order/create` | 代客下单：入参 = ORDER-2 全集 + `user_id?`（散客可空）；`salesman_id` **由会话强制注入**，入参给了也忽略；越权指定别人的 `distributor_id` → 403 |
| SO-E2 | GET | `/order/getlist` | 我开的单：分页 + `order_status`/`keyword`，只出 `salesman_id=自己` 的行 |
| SO-E3 | GET | `/order/getinfo` | 详情（同 ORDER-5 字段集，外加 `salesman{}`） |
| SO-E4 | POST | `/order/ziti` | 员工端自提核销：`{order_id(Y), ziti_code(Y)}` = WO-A2 的另一个入口（同一 service 方法，不复制逻辑） |

### 18.4 店铺商品双模：价格与库存的唯一口径

`is_total_store` 决定**库存从哪个池子扣**，与价格是两个独立维度：

```
行价格 = distribution_distributor_items.price（该店挂过品） ?: items.price（总部现价）
行库存 = is_total_store=TRUE  → items.store（总部池，CAS deductStock 一条腿不变）
        is_total_store=FALSE → distribution_distributor_items.store（店铺池，新增 CAS）
上下架 = is_show / is_can_sale 任一 false → 前台列表不出、购物车进 invalid_cart（原因"该店铺已下架此商品"/"该店铺暂不可售"）
```

三条硬约束：① 店铺价与店铺库存**只在 `CartService.buildContext` 预取**（与营销域同一条纪律，规则链继续不查库），`PriceLine` 新增 `stockSource`（`TOTAL`/`SHOP`）与 `shopRowId`，`price` 直接换成店铺价、`origin_price` 仍给总部现价；② 扣减与回补必须**同一条腿对称**——`OrderCreateService.deductStock` 按行 `stockSource` 分流，取消/超时关单/售后回补同样分流，漏一条就出现"店铺卖光了、总部库存纹丝不动"；③ 未挂品的商品在店铺视角**不参与**该店购物车与下单（`distributor_id` 与 `shop_id` 不一致时进 invalid），跨店混 cart 只是展示分组，结算仍是**一单一店**（`form.shop_id` 决定这单归谁，拆单不属 M1-4）。

### 18.5 佣金与提现：入账、冲销、结算、状态机

入账落在**支付回调成功之后**（`PayService` 结清处，与积分入账 `accruePoints` 同位）：按订单行逐行写 `distribution_distribute_logs`，`rebate = 行 total_rebate`（下单时已按 `items.rebate × num` 算好，与订单明细同源，不重算费率）、`total_rebate` 记累计、`is_close=FALSE`、`plan_close_time = 结清时刻 + sys_config.commission.close_delay`（天，seed 追加，缺省 7）。三条硬口径：① 只有 `distributor_id>0` 且 `order_holder='distributor'` 的单才产生佣金（自营单平台不抽佣）；② 平台服务费与佣金分账（`payment_subject=1` 店铺收款时平台按 `rate` 收服务费）属 statements 账期域，**M1-4 只做列与展示，不做对账单**；③ 重复回调不双计（幂等判据 = 该 `order_id` 已有日志行就跳过，与 M1-2 的 `trade_state` 判据同族）。

冲销改在同一个事务里做两件事：现有 `reverseCommission` 减订单列（`total_rebate`/`commission_fee`）之外，新增**按行退款占比减对应日志行的 `rebate`**（退款额占比向下取整，且 `rebate` 不为负——减到 0 为止）。已 `is_close` 的日志行**只记负向流水不回改**（结算后的钱已经进了可提现余额，回改历史行会让账实不符）。

提现状态机（`distribution_cash_withdrawal.status`，照抄源系统四态）：

```
applied ──confirmed──> confirmed ──paid──> paid（终态，记 paid_time）
   │                        │
   └──refused──> refused     └──refused──> refused（终态，钱回可提现余额）
```

余额口径：`balance = Σ(is_close=TRUE 的 rebate) − Σ(status IN ('applied','confirmed','paid') 的 money)`。申请闸门（全部 422 且带 `errors`）：`money<=0`、`money>balance`（"可提现余额不足"）、该店已有 `applied/confirmed` 在途（"已有提现申请待处理"）、店铺 `is_valid!='true'` 或 `review_status=false`（"店铺未启用，不能提现"）。`refused` 不写日志行、不动佣金表——它只让上面的 `balance` 公式少扣一笔，因此**天然可重算**。

### 18.6 自提核销与 `ziti_code`

`receipt_type='ziti'` 建单时（`OrderCreateService`，本轮把"只放宽收货校验"补成完整语义）：校验 `distributor_id` 对应的店 `is_ziti=TRUE` 否则 422「该店铺不支持自提」；生成 **6 位数字码**（`SeqGenerator` 新号段 `ziti_code`，非随机，同店不重复即可）写 `ziti_code`、`ziti_status=FALSE`；订单状态沿用 C42 的 `status=4`「待自提」语义（付款后由状态机推到 4，而不是 5 待收货）。

核销（WO-A2 = SO-E4 同一 service）四条闸门：① 单必须 `receipt_type='ziti'`；② `ziti_status=FALSE`（已核销 422「该单已核销」）；③ `order_status` 必须在 4（未付款/已关闭不能核）；④ **码逐字相等**（不区分前后空格，不做大小写）。通过后：`ziti_status=TRUE`、`order_status` 推到完成（自提单没有发货环节，直接置 3 已收货），并写 `orders_operate_logs`（操作人=核销员工/运营）。**并发核销**用条件更新 `WHERE order_id=? AND ziti_status=FALSE` 做 CAS，两路同时只有一路改到行（与营销域同一套 CAS 纪律）。`ziticode` 前台查询只出本人该单，码不给别人看。

### 18.7 权限码与号段（`sql/seed.sql` 末尾追加，接 M1-3 的 37 之后）

| resource_id | resource_code | 覆盖端点 |
|---|---|---|
| 38 | `distributor`（catalog） | 目录 |
| 39..42 | `distributor.lists` / `.update` / `.review` / `.status` | DS-A1..A8（看/改/审/开关四条分立，"能改档案"不等于"能自己过审"） |
| 43,44 | `distributor.items_lists` / `.items_update` | SI-A1..A4 + SI-F1（挂品与改价是两个岗） |
| 45,46 | `salesman.lists` / `.update` | SM-A1..A5 + 角色 SM-A6 |
| 47 | `distribution.logs` | DL-A1/A2 + CW-A3 的"看钱"面 |
| 48 | `distribution.close` | DL-A3 结算（**独立一条**：结算是把钱从"在途"变成"可提"的动作） |
| 49,50 | `withdraw.lists` / `.check` | CW-A1/A2（申请方与审核方必须分人，与 M1-1 退款岗同一理由） |
| 51,52 | `pickup.lists` / `.update` | PL-A1..A5 |
| 53 | `order.writeoff` | WO-A1/A2（核销=承认货已到手） |
| — | 员工面（ST-E/SO-E）**不吃 resource_code** | 鉴权=SALESMAN JWT + 会话内 `salesman_id/distributor_id` 强制过滤，越权面靠 §18.3 的"会话注入"而不是运营权限树 |

号段：`seq_generator` 追加 `('ziti_code',0)`；新表主键沿用 AUTO_INCREMENT + `RESTART WITH 100`（与 M0-4 九表同惯例）。
schema 末尾追加两条列（第三批落地时一次给全，MySQL/H2 同一语句可跑）：`distribution_distributor_salesman.password VARCHAR(255)`（员工端登录口令，bcrypt，与 `operators` 同列宽同算法）、`members.salesman_id BIGINT DEFAULT 0`（会员当前归属导购）。

### 18.8 分批交付

| 批次 | 内容 | 端点 |
|---|---|---|
| 第一批 | 店铺档案（DS-A/F）+ 店铺商品双模（SI-A/F）+ **算价与扣库存按 `stockSource` 分流** + 多店铺混合购物车拆单展示 + 后台订单 `distributor_id` 越权过滤 | DS-A1..A8 / DS-F1..F2 / SI-A1..A4 / SI-F1..F2 |
| 第二批 | 佣金入账/冲销/结算（DL）+ 提现四态链（CW-A/F）+ `sys_config.commission.close_delay` | DL-A1..A3 / CW-A1..A3 / CW-F1..F4 |
| 第三批 | 自提点（PL）+ `ziti_code` 生成与核销（WO/ZT-F1）+ 导购（SM）+ **员工端第三条链**与 SO-E 族 | PL-A1..A5 / PL-F1 / WO-A1..A2 / ZT-F1 / SM-A1..A6 / SM-F1..F2 / ST-E1..E5 / SO-E1..E4 |

门槛用例（docs/05 §M1-4）：① **多店铺混合购物车拆单展示** —— 总部店与加盟店各挂同一 SKU 的不同价/不同池，`CART-2` 两组各按自己店铺价与店铺库存出数、运费按组各算一次、`invalid_cart` 收"该店已下架"的行；下单只成交所选那一店，另一店的池子分毫不动。② **`distributor_id` 过滤越权** —— 用运营令牌（挂 role）看后台订单/佣金/提现列表时按 `distributor_id` 收窄，`viewer`（seed 无角色）403、跨店 id 404 而不是 200 回显别人的数据；员工令牌打会员面与后台面各自 401。

### 18.9 与源系统的差异（D 系列续编，追加于 §16.2/§17.2 之后）

| # | 变更 | 理由/缓解 |
|---|---|---|
| D42 | 店铺"审核"与"启停"是两个端点，不像源系统混在 `updateDistributor` 里 | 审核位 `review_status` 只由 DS-A5 改，运营开关 `is_valid` 只由 DS-A6 改（与营销域"审核与启用是两个维度"C115 同一条纪律）。`closed` 关店要求先把佣金结清、提现处理完，源系统不查这一步，会出现"店关了、账上还挂着未结佣金"的悬空数 |
| D43 | 店铺商品不引入 Redis/缓存与"批量导入导出" | SI-A1..A4 全部走数据库条件更新；`items/export`（L521）与 `wxacode`、标签分类、围栏等一并划 P2（§18 开头的"不做"清单），本项目演示形态是单机 H2 + 无 Redis |
| D44 | 员工端会话用 JWT，不还原 PHP 的 `x-wxapp-session` 服务端会话 | header 名沿用 `x-wxapp-session` 保住线协议形状，值是本项目的 `Kind.SALESMAN` 令牌。源系统"停用导购后会话仍活"的缺陷不继承：员工面每个请求都回查 `salesman.is_valid`，停用即刻 401（多一次索引查询，换来"停用真的停用"） |
| D45 | 导购不做任务/通知/排行榜/赠券/客诉 | 附录里这些占导购域 60+ 条路由，全部 P2；M1-4 只落"人员 + 角色 + 归属 + 佣金 + 代客下单"这条经营主干 |
| D46 | 佣金按**行 rebate 快照**入账，不在结算时重算费率 | 源系统在回调里重读商品 `rebate`/`commission_ratio`，改一次费率就把历史单的佣金改掉。本项目下单时已把 `items.rebate × num` 落到 `orders_normal_items.total_rebate`，入账只读它（与"优惠快照后面会被改被删"同一族：C121）。`rate`（万分比平台服务费）只影响之后新单 |
| D47 | 提现"打款"是 mock 回执，不接渠道代付 | CW-A3 只回账户型信息与 `paid_time`；bady/adapay/hfpay 三家渠道提现族（附录 15 条）属 M2-2「只做 `PayChannel` 实现位，不逐家联调」 |


## 19. 实现期语义补充（M1-4 第一批：店铺档案 + 店铺商品双模落地时确认，追加于 §18 之后，不回改上文）

交付面：`DS-A1..A8` / `DS-F1..F2` / `SI-A1..A4` / `SI-F1..F2` 十二条端点 + §18.3 数据权限（`operators.distributor_ids` → JWT → `DistributorScope`）+ §18.4 双模在**算价与扣库存两侧**的分流。新代码落位：`dao/distribution/{DistributionMappers,ShopItemMappers}.kt`、`service/distribution/{DistributorService,ShopItemService,StockPool}.kt`、`web-admin/controller/{DistributorAdminController,DistributorFrontController}.kt`。

### 19.1 口径确认（C 系列续编）

| # | 口径 | 实测依据 |
|---|---|---|
| C141 | **失效行的字段集与有效行同构**：`invalid_cart`/`invalid_items` 的行同样带 `store`（本店池现值）、`is_total_store`、`stock_source`、`origin_price`。购物车里"这家店卖 59、总部 89、本店池子已经空了"这件事只能由行自己说清，否则会员看到的是一条没有来由的失效记录 | `CartService.pendingView` 一处装配、两面共用；`DistributorEndToEndTest` 门槛① 用例先照出缺失（`缺失=[origin_price, stock_source]`），补齐后 `ContractDiff` 两面同键 |
| C142 | **下单侧的失效原因与 ORDER-1 同源**：`OrderCreateService.reason()` 改为直接调 `CartService.invalidReason(p)`（原私有、现公开）。判序是 `商品不存在 → 该店铺已下架此商品 → 该店铺暂不可售 → 店铺库存不足/库存不足 → 商品已下架` | 实跑照出来的：加盟店把 `is_can_sale=false` 之后，总部池还满着 200，旧 `reason()` 那套三行 `when` 报「库存不足」，预览却写着「该店铺暂不可售」——与 §15 那条"拒券文案与判据必须同源"完全同族 |
| C143 | **`price=0` 就是"跟随总部现价"**，不是"卖 0 元"：SI-A1/SI-A3 都不写 0 进 `price` 的判空分支，`ShopItemService` 出数一律 `shopPrice.takeIf { it > 0 } ?: items.price` | `m14_ds_smoke.py`「预清理后本店价回落总部现价」+ `price_store(shop=SHOP)` 收尾断言 `== 8900` |
| C144 | **`is_total_store` 与 `is_show/is_can_sale` 是三件独立的事**：前者只决定扣哪个池，后两位只决定可售性。SI-A1 的 upsert 因此**只覆盖传进来的字段**（先读旧行合并再整行 UPDATE），"改个价"不会把上架位清零、"改池子归属"不会把库存抹掉 | 冒烟「SI-A1 重复挂品只覆盖传进来的字段（池子与两态位保留）」：第二次只传 `price=6900`，`store=30`/`is_show`/`is_can_sale` 原样 |
| C145 | **数据权限越权有两面，语义不同**：列表面（`GET /api/distributors`、`/api/distributor/items`、`/api/orders`）越界=**过滤到 0 行**，因为 `distributor_id` 是查询参数、`DistributorScope.narrow` 取交集；资源引用面（`/distributors/info`、`/distributor/count/{id}`、`/api/orders/{id}`、`PUT /distributor/{id}`）越界=**404 而不是 403**，不给可枚举的信息 | 冒烟门槛② 七条：`distributor01` 列表只出 `{2}`、`?distributor_id=1` 回 `total_count=0`、店 1 的 info/count/items/PUT 全 404、别人家店单 404 而自己家店单 200 |
| C146 | **权限码与数据权限是两条线**：`distributor.update`（改档案）过 != `distributor.review`/`distributor.status` 过。seed 的角色 2 只给 lists/update/items_lists/items_update，所以 `distributor01` 打审核与启停是 403，打 `payment-subject` 是 200 | 冒烟「有 update 码但没 review 码 → 403」+「运营开关另有一条码，同样 403」+「payment-subject 放行」 |
| C147 | **`is_valid='closed'` 是终态**：DS-A6 只能从 `'true'/'false'` 进 `closed`（UPDATE 带 `AND is_valid = #{from}`），改回 `'true'` 一律 422「已关店，请走重新开店」。关店之后**档案仍可改**（善后要填联系电话），只有挂品被拦：`SI-A1` 对 `closed`/`false` 的店 422 | 冒烟「closed 是终态」+「关了店的店不许再挂品（422）」；e2e Order 6 同两条 |
| C148 | **前台与后台读同一份列**：`DS-F1/F2`、`SI-F1`、`items_price_store?distributor_id=` 的准入判据都是 `is_valid='true' AND review_status=TRUE`，未营业的店对前台是 **404**（不是空列表、不是 403） | 冒烟「未过审/已关店的 B 不进前台」+「DS-F2 未营业的店对前台就是 404」 |
| C149 | **`balance` 是纯派生数，不落列**：`Σ(is_close=TRUE 的 rebate) − Σ(status IN ('applied','confirmed','paid') 的 money)`。`paid` 也占（`commission_closed` 只增不减，已打款若不扣就能被反复提空）；`refused` 不占（钱从未离开余额，整个公式对拒绝申请可重算） | e2e Order 8：closed 5000 / open 3000 / applied+paid 占用 3000 / refused 不占 → `balance=2000`，`withdraw_in_flight=3000` |
| C150 | **取消挂品不改历史**：`SI-A4` 只 `DELETE distribution_distributor_items`，`orders_normal_orders_items.distributor_id/is_total_store` 是当时的事实，删行之后回补仍按订单行走 | e2e Order 16 收尾：`shop_store` 归 0、`items.store` 未惊动、`COUNT(*) … items WHERE order_id=? AND distributor_id=2` 仍是 1 |

### 19.2 与源系统的差异（D 系列续编）

| # | 变更 | 理由/缓解 |
|---|---|---|
| D48 | **未挂品的行回落总部态，而不是判"该店不可售"** | 源系统 `DistributorItems` 查不到行就把商品从店铺列表里摘掉。本项目 `auto_sync_goods=1` 的常态就是"店里没有独立行"，判不可售会让加盟店一上架就整车失效、演示链当场死。缓解：回落的只有**价与池**，可售性仍由 `items.approve_status` 与总部池判；`stock_source` 因此显式回 `TOTAL`，前端要看得出这行读的是总部池 |
| D49 | **扣减/回补收口到唯一 `StockPool.deduct/restore`**，四处原先各自调 `ItemsMapper.deductStock/restoreStock` 的调用点全部改走它 | §18.4 写"扣减与回补必须同一条腿对称"，代码里只有一种兑现方式：让扣和回补共用一个方法。原来四处分流，只要有一处忘了判 `is_total_store`，就会出现"店铺卖光了、总部库存纹丝不动"或"取消一单把总部池加两次"。调用方只带 `(店, 品, 件数, is_total_store)`，池子归属读**订单明细行**不回读挂品行（挂品行可能在两单之间被改被删，C150 同族） |
| D50 | **列表派生数走"一次 GROUP BY 批量回捞"，不做逐行子查询** | `DS-A2` 的 `order_count/pay_fee/refund_fee/commission_fee/balance` 五条聚合各一条 SQL、按本页 `distributor_id` 集合 `IN` 查，与商品域 `ItemService.enrich` 同一条防 N+1 纪律。退款额不并进订单那条聚合：`orders_normal_orders` 没有 `refund_fee` 列，钱在 `trade.refunded_fee`，跨表 JOIN 要在 H2/MySQL 两种方言下各写一遍 `CAST(order_id AS CHAR)`，拆两条 GROUP BY 更便宜且不漂 |
| D51 | **`distribution_distributor_items` 不实现 `Stampable`** | 本表按源系统 schema 原样沿用，**没有 `created/updated` 两列**；照抄 M0-4 记过的那条会给 `SeedDataLoader` 与 `INSERT` 一起报 `Column "created" not found` |

## 20. 实现期语义补充（M1-4 第二批：佣金 DL + 提现 CW 落地时确认，追加于 §19 之后，不回改上文）

交付面：`DL-A1..A3` / `CW-A1..A3` / `CW-F1..F2` 八条端点 + §18.5 的入账三条硬口径、冲销两段式与提现四态机 + `sys_config.commission.close_delay` + 权限码 47..50（§18.7）。新代码落位：`dao/distribution/CommissionMappers.kt`、`service/distribution/{CommissionService,CommissionSettleJob}.kt`、`web-admin/CommissionAdminController.kt`、`web-front/CommissionFrontController.kt`；`PayService` 的三个结清点与 `RefundService.reverseCommission` 各挂一行调用——**钱动的那条事务里必然同时动佣金流水**，不再有"付了钱没佣金、退了款佣金还在"的中间态。

### 20.1 口径确认（C 系列续编）

| # | 口径 | 实测依据 |
|---|---|---|
| C151 | **入账只跟钱走，幂等判据是"这一单有没有流水行"**。`accrue` 挂在结清事务内、与 `accruePoints` 同位；挡重放用 `COUNT(order_id)>0` 而不是订单状态——状态在取消/售后/后台改单里都会被别处动，流水行不会 | e2e「未付款的单不入账」（DL-A1 0 行）+「回调重放不双计 零佣金不写行」（重放一次仍 1 行）；冒烟同名两条（`POST /api/notify/wxpay` 打两遍，`logs?order_id=` 的 `total_count==1`） |
| C152 | **流水的 `rebate` 与 `total_rebate` 都写订单行的 `total_rebate`（该行佣金总额，不是单件）**：聚合式 `SUM(rebate)` 与 `SUM(total_rebate)` 必须同值可互换，否则 `balance` 与统计卡的"佣金合计"会差一件的钱。`num` 只记成交件数，**负向行 `num=0`**——退款改的是钱不是销量 | e2e「结清即入账 佣金取行上的 `total_rebate`」断 `rebate==total_rebate==1000`；冒烟「补一条等额负向行」+「负向行天生已结算、件数记 0」 |
| C153 | **提现的资格判据排在额度判据之前**：四道闸门顺序是 表单合法 → 归属（在册员工行）→ 店铺营业态 → **在途唯一** → 余额。反过来的话，一家已经把额度提满的店再点一次，看到的是"可提现 0 分"这种把人往错误方向引的原因，而他真正该看到的是"上一笔还没处理完" | e2e「CW-F1 四道闸门」首跑就红在这里（第二笔拿到 422「可提现余额不足」而不是「已有提现申请待处理」），把 `countInFlight` 提到 `balanceOf` 之前后转绿；冒烟「在途唯一 → 422（资格判据排在余额之前）」 |
| C154 | **会员面的"这是不是你的店"只认 `distribution_distributor_salesman` 的在册行**（`user_id + distributor_id + is_valid='true'`）。`distribution_distributors` 没有"老板"列，源系统靠 `salesman.user_id` 反查，本项目照抄这条判据；seed 因此追加一条 `demo 旗舰店`↔演示会员的在册行 | e2e/冒烟「不是你的店 → 403（归属只认在册员工行）」：同一令牌打 `distributor_id=1` 是 403，打 `=2` 是 200 |
| C155 | **`cash_withdrawal` 没有 `paid_time` 列**（照源系统 schema 原样），终态时刻一律落 `updated`；出参 `paid_time` 只在 `status='paid'` 时给该值，其余状态**给 0 而不是 null**，前端不用判空 | 冒烟「落 `applied` 且 `paid_time=0`」「`confirmed→paid` 200，终态时刻落在 `updated`」「打款后 `paid_time>0`」；e2e「CW-A3 打款前先给待财务打款」 |
| C156 | **DL-A1 的 `order_id`/`order_no` 是同值的两个字符串位**：本仓 `orders_normal_orders.order_id` 就是号段发出来的单号，表里没有 `order_no` 列。两个键都给是让后台拿去显示、前端拿去跳详情，谁都不用知道"其实是一个数"。**昵称取 `members_info.username`，空则退到掩码手机号**；`members`/`members_info` 都 `LEFT JOIN`——注销会员的佣金流水仍要出得来，钱不能因为人已注销就从账上消失 | 冒烟「单号出参是字符串且与 `order_no` 同值」+「买家昵称出得来」；e2e 契约 `CommissionContract.LOG_ROW` 含 `order_no` |
| C157 | **到期判据只有一条，与入口无关**：`is_close=FALSE AND plan_close_time<=to_time`。`DL-A3`（运营手动）与 `CommissionSettleJob`（定时）共用它，差别只在 Job 没有租户上下文，所以 DAO 另给一条不带 `company_id` 的 `closeBatchAll`；两者都是条件更新，多实例同时扫只有一份副作用落地 | e2e「结算只由到期判据决定 与入口无关」（Job `runNow()` 结掉的行与 `DL-A3` 同一批）；冒烟「`DL-A3` 到期一次结清」+「同一时间窗第二次 0 行（幂等）」 |
| C158 | **`fastbuy` 的店铺取 `items.distributor_id`，与 `distributor_id` 入参无关**——商品挂在总部名下时，`fastbuy` 单永远归总部。要造"加盟店佣金"只有**进店加购 + 车内结算**（`CART-*` 带 `distributor_id` → `order_new {cart_type:'cart', distributor_id}`）这一条路 | e2e 首跑 13 例红的根因就是借了 `PayEndToEndTest` 的 `fastbuy` helper；改成车内结算后 16 例降到 2 例（剩下那 2 例是 C153 与 C159 的真问题） |
| C159 | **可提现余额是负数兜 0 的派生数**，`apply` 闸门读的是现算聚合（`balanceOf`），与 `DL-A2`/`DS-A8` 共用 `OccupyingSum` 一处口径；冲销找不到对应正向行时**不记负向行**——那笔佣金从没进过账，凭空冲一笔会让全场 `commission_total` 变负，那是更难解释的账 | e2e「账本最终对得上」（`closed=1000 / total=500 / balance=0`，负向行必 `is_close`）；冒烟「账自洽：`balance = max(0, 已结算 − 占用)`」 |

### 20.2 与源系统的差异（D 系列续编）

| # | 变更 | 理由/缓解 |
|---|---|---|
| D52 | **冲销分两段**：未结算行直接减（带 `GREATEST(…,0)`，减到 0 为止），已结算行**不回改**，改成插一条等额负向流水（`is_close=TRUE`、`num=0`） | 那条正向行的钱早进过 `balance`，回改历史行会让"页面上看到过的余额"和账本对不上。负向行走同一张表、同一组 `SUM(CASE WHEN is_close …)`，聚合式不改一行就把已结算总额拉回正确位置；对账时两行都在，能讲清发生了什么。缓解：退款占比按**行成交额**算（`1000×8900/17800=500`），与订单头 `total_rebate` 的冲销同一个分子分母，两处判据不漂 |
| D53 | **提现状态机在 service 判 + 条件更新兜并发**，不给表加"状态机列"或流转日志表 | `transit(id, from, to)` 的 `WHERE status=#{from}` 让"两个人同时点通过"只有一次生效，第二次 0 行→422「该申请已被处理」；非法目标（`applied`）400 走校验面，跨态与终态 422 走业务面——与 §15 券、M1-1 退款那两处一致 |
| D54 | **`DL-A3` 的 `closed_fee` 只在"实际改到的行数==预览行数"时才给数**，否则给 0 | 先 `closePreview` 取数、再 `closeBatch` 置位是两条语句，并发下预览有数、更新 0 行。出参报预览的钱等于报一笔没发生的结算；`closed_count` 取 `UPDATE` 的受影响行数才是真话 |
| D55 | **定时结算做成 `@Scheduled` 固定延迟的一个 Job（`CommissionSettleJob`），不还原源系统的 `cron` 表与任务中心** | 与 M0-6 超时关单、M1-3 拼团结算同一条纪律：条件更新 + 可配 `ecx.schedule.*`，测试与冒烟把 initial-delay 拉到 1 小时以免抢跑（`settleDueAll` 与 `runNow()` 给用例直接调）。源系统任务中心的启停/重试/通知一并划 P2 |

## 21. 实现期语义补充（M1-4 第三批 3A：自提点 PL + 取货码核销 WO 落地时确认，追加于 §20 之后，不回改上文）

交付面：`PL-A1..A5` / `PL-F1` / `WO-A1..A2` / `ZT-F1` 九条端点 + §18.6 的建单发码四条闸门、核销四闸门与"核销即完成" + `DS-F2` 追加 `pickup_locations[]` + 权限码 51 `pickup.lists` / 52 `pickup.update` / 53 `order.writeoff`（§18.7）+ 号段 `ziti_code`。新代码落位：`dao/distribution/PickupMappers.kt`、`service/distribution/PickupLocationService.kt`、`service/order/WriteOffService.kt`、`web-admin/PickupAdminController.kt`、`web-front/PickupFrontController.kt`；`OrderCreateService` 在**算价之后、扣库存之前**挂 `zitiPlan()`，`OrdersMapper` 加 `pickup_location_id` 列与一条 CAS `writeOff`。

### 21.1 口径确认（C 系列续编）

| # | 口径 | 实测依据 |
|---|---|---|
| C160 | **"这是谁的点"看 `distributor_id`，"谁的单能用这个点"看 `rel_distributor_id`，两列各有各的出口**：后台列表按前者收窄（§18.3 数据权限），前台取点按 `归属本店 OR rel=0 OR rel=本店`。`PL-A2` 不传 `rel` 就是 **0 = 使用方不设限**，所以一家自己没建点的加盟店仍会靠平台通用点成交——§18.6 那条"该店还没有可用自提点"在演示库里几乎走不到 | e2e 用例 1「店铺账号只看得到自己归属的点」+ 用例 5「平台通用点每家都看得到」；冒烟「PL-A2 默认启用；不传 rel 就是 0」+「本店一个点都没建，但全平台通用点那条通路让它成交」 |
| C161 | **停用闸门数的是"未完结且还可能来取货"**：`ziti_status='false' AND order_status NOT IN ('DONE','CANCEL')`。首版只排 `DONE`，冒烟第二轮当场死——一张**永远不会有货被取走**的取消单把那个点永久锁死，门店再也停不掉 | 生产改动：`PickupMappers.countOpenZitiOrders`；e2e「取消单不拦停用」（先 422 后 200 两段）；冒烟「取消掉那张未付款单之后，它占着的平台通用点也停得下来了」 |
| C162 | **核销四道闸门的顺序与错误形状**：非自提单 422 → 未付款 422 → 码逐字不符 422 → 已核销 422；**缺字段才 400，空串算"传了但对不上"走 422**；码只 `trim()` 两侧空白（§18.6"不区分前后空格"）。查询面 `WO-A1` 不设付款闸门（输码前总得先看清这一单），但码仍逐字比 | e2e 用例 9「闸门一条条拦」四段 + 用例 8「查询面也逐字比」；冒烟「空串走比对而不是「没传」：422」「缺字段才 400」「码两侧空白算 trim」 |
| C163 | **防双核靠 `UPDATE` 的受影响行数，不靠先读后判**：`WHERE receipt_type='ziti' AND ziti_status='false'` 回 0 行就是别人已经核过 → 422「该单已核销，请勿重复操作」。两路同时读到 `ziti_status='false'` 是允许的，落地只有一路 | e2e 用例 11（两条线程各发一次，断言 200 恰有一次、库里 `end_time` 只落一次）；冒烟「闸门④：重复核销 422」 |
| C164 | **核销即完成，一枪三态**：`order_status=DONE` + `delivery_status=DONE`（自提没有发货环节）+ `end_time=now` + **清空 `auto_cancel_time`/`auto_finish_time`** + `syncAssoc` + `orders_operate_logs(behavior='writeoff', operator_role='seller')`。两个自动时间必须清空，否则定时器会去动一张已完成的单 | e2e 用例 10（状态/时间/清空/关联/留痕五段）；冒烟「两个自动时间清空」「`end_time` 落在核销这一刻」「会员侧文案变「已失效」」 |
| C165 | **单上不存取货点快照，只存 `pickup_location_id`**：核销页与会员码页读的都是点的**现值**（改了门牌显示新门牌），**停用中的点也照样读得到**并如实回 `status=0`。历史单的"我在哪儿取的钱"不能被一次停用抹掉 | e2e 用例 15；冒烟「核销页显示的是改后的地址，不是建单那一刻的」「历史单的取货地址不能被「停用」抹掉」 |
| C166 | **码是建单那一刻发出去、存下来的**：`"%06d".format(seq.next(ZITI_CODE) % 1_000_000)`（号段值取末 6 位，非随机、可回溯），同一秒内两单靠号段游标分开；再建一单也不回改上一单的码。`seq_generator` 缺 `ziti_code` 那一行时 `SeqGenerator` 的 `check(touched==1)` 直接抛异常 → 建单 500 而不是 4xx，**所以 schema 必须 `INSERT IGNORE` 那一行** | e2e 用例 6「两单两张码」「码是存下来的」；冒烟「建单即发码：6 位数字」（清 `data/` 冷启动验到 `ziti_code` 行自动补齐） |
| C167 | **权限码判在归属之前**（§18.7 给店铺运营岗只配 51 `pickup.lists` + 53 `order.writeoff`）：建/改/停/挂店一律 **403**，不是"先试归属再报 404"；而**数据权限越界在引用面仍是 404**（店铺账号查别人店的自提单）。两条线互不替代（C145/C146 在点面上复现） | e2e 用例 2/3/14；冒烟「连建自己店的点也是 403：权限码判在归属之前」「别人店的单：店铺账号 404（不是 403）」 |
| C168 | **`shop_name` 是 JOIN 出来的展示列，但必须进 `PL-A2/A3` 的单行出参**：DO 没有这一列，`viewOf` 回查店名补上；平台通用点（`distributor_id=0`）回**空串而不是 null**（列表与单行同一渲染位）。另：H2 里 JOIN 别名**必须写 snake**（`d.name AS shop_name`），驼峰别名会被 `DATABASE_TO_LOWER` 整段小写丢弃 → 永远读不到值 | e2e 用例 1「demo 旗舰店 JOIN 出来」；冒烟「PL-A2 出参形状与列表一致」+「seed 追加的两条演示点在常驻库里真的存在」（别名写错时这一条就是空串） |
| C169 | **会员侧的判据是"人的归属"，不是数据权限**：`ZT-F1` 按 `user_id` 认，不是本人的单 404（不给"这单存在"）、未登录 401 `Unauthenticated.`、非自提单 422；会员面给自己看码时电话**不掩码**（他打的就是这个号），而 `PL-F1` 匿名面一律掩码（前 3 后 4）、`distance` 只在传了坐标时出数（**没传也保留键**：Jackson 未配 `NON_NULL`） | e2e 用例 7/5；冒烟「会员面给自己看码：电话不掩码」「前台电话一律掩码（前 3 后 4）」「传坐标才出 distance」 |
| C170 | **`@Audited` 的路由名就是权限码字符串**（`pickup.update` / `order.writeoff`），落 `operator_logs.route_name`（列名不是 `route`）；`SUP-4` 的 `keyword` 筛的正是 `route_name LIKE`，冒烟因此能用**差值**证明"这一轮留了痕" | 冒烟「审计：本轮的写路径都留了痕」「审计行里能看到这条路由是哪个账号写的」；e2e 用例 14 直接 SQL 断 `route_name IN (…) >= 5` |
| C171 | **建单的四条自提闸门排在扣库存之前**：店铺存在 → `is_ziti` → 营业态（`is_valid='true'` 且过审）→ 点可用（显式选点走 `requireUsable`，未选走 `defaultFor`）。被拒的每一次**分毫不动库存池**，与取消回补共用 `StockPool` 那一条腿 | 冒烟「未开自提的店走 ziti → 422」+「闸门在扣库存之前：这一拒分毫没动池子」+「取消回补同一条腿」；e2e 用例 6「选了一个不属于这家店、也不通用的点 → 422」 |

### 21.2 与源系统的差异（D 系列续编）

| # | 变更 | 理由/缓解 |
|---|---|---|
| D56 | **自提单的运费没有做 0 化**（已知未做，不是实测口径）：源系统 `receipt_type='ziti'` 的 `freight` 恒 0，本项目仍按模板算运费并计入 `total_fee` | 算价链在 M0-5 收口成唯一 `quote`，运费在 `PriceContext` 里与营销减免有叠加顺序；本轮只在建单侧加自提闸门、没动算价侧。**验证方法**（若要改）：`receipt_type='ziti'` 建单后断 `freight_fee==0` 且 `total_fee==item_fee`；当前冒烟刻意没断这两条，以免把差异写成通过 |
| D57 | **平台通用点（`distributor_id=0`）只有 `admin` 型建得了**：`create` 允许 0，但先过 `scope.require(0)`——店铺账号的可见集合永不含 0 | 通用点是平台的权力，不是任何一家店的权利：一个加盟店把点挂成通用就等于把取货地址变成公共资产。§18.6 只说"通用点"，没规定谁能建，这是本项目的裁定 |
| D58 | **`status` 不"顺手归一"成 1**：入参只认 1/0，脏值（7）400 走校验面 | 把 7 当"启用"存进去，`PL-A1` 的 `status` 过滤器就再也筛不到这类脏值，运维看到的世界与库里不一致 |
| D59 | **`PL-A4` 是逻辑删（`status=0`），行不真删** | 与 C165 同一条理由：历史自提单要能回看取货地址；前台一律筛掉，后台 `?status=0` 还能筛到，`WO-A1` 读现值时不设"必须启用"这道门 |
| D60 | **`PL` 族没有"按 id 单读"的 GET**（源系统同形：配置台只有列表 + 表单），因此 `PL-A2/A3` 的出参必须是完整行 | 前端建完点直接把这一行插进列表，两个形状不一致就得各写一套渲染。e2e 首跑按 `GET /api/pickuplocation/{id}` 断言吃了 **405**，改成断 `POST` 出参并给 `viewOf` 补 `shop_name`（C168）才对得上 |
| D61 | **`pickup_location_id` 用 `ALTER TABLE … ADD COLUMN IF NOT EXISTS` 追加，并配复合索引 `idx_ziti_pickup(pickup_location_id, receipt_type, ziti_status)`**（C161）| 常驻库重启自动补列补索引，不必清库；停用闸门每改一次点都要按这三列扫 `orders_normal_orders`，没索引就是一次全表扫 |
| D62 | **`WO-A2` 与员工端 `SO-E4` 的核销只留一个实现**（本批为 3B 预留的口径，尚未落地）：差别只在归属判据（后台按 `DistributorScope`，员工按在册行），码比对与状态副作用共用 `WriteOffService.writeOff` | 两处各写一遍就会出现"后台核完的单员工端还能再核一次"这种双花；共用一条 CAS 从结构上堵掉 |

## 22. 实现期语义补充（M1-4 第三批 3B：导购 SM + 员工端链 ST/SO + 岗侧提现 CW 落地时确认，追加于 §21 之后，不回改上文）

交付面：`SM-A1..A6` / `SM-F1/F2` / `ST-E1..E5` / `SO-E1..E4` / `CW-F3/F4` 共 **19 条端点** + 第三条安全链 `staffChain`（`@Order(0)`、`/api/admin/wxapp/**`、规范头 `x-wxapp-session`、主体 `Kind.SALESMAN`）+ §18.3 员工面五条强制口径 + §18.5 的导购侧提现 + 权限码 45 `salesman.lists` / 46 `salesman.update`（§18.7）+ 新表 `distribution_distributor_salesman_role` 与六处 schema 追加（`salesman.password`、`salesman.avatar`、`uk_salesman_mobile(distributor_id, mobile)`、`distribute_logs.salesman_id`、`cash_withdrawal.salesman_id`、`members.salesman_id`）。新代码落位：`dao/distribution/SalesmanMappers.kt`、`service/distribution/SalesmanService.kt`、`service/auth/SalesmanContext.kt`、`web-admin/security/JwtSalesmanFilter.kt`、`web-admin/SalesmanAdminController.kt`、`web-admin/StaffController.kt`、`web-front/SalesmanFrontController.kt`；改动面：`SecurityConfig` 加 `staffChain`、`CommissionService`/`CommissionMappers` 加岗侧聚合与两列、`OrderCreateService` 加 C177 回落、`MembersMapper` 加 `bindSalesman`/`salesmanIdOf`。

### 22.1 口径确认（C 系列续编）

| # | 口径 | 实测依据 |
|---|---|---|
| C172 | **员工登录认手机号，不认 `user_id`**：`findLogin(companyId, mobile, distributorId?)`。`distributor_id` 传错店=查无此人，与"手机号不在册""口令不符"**同一句** `Unauthenticated.` 401，不回答"这个号在别家店存不存在"；不传 `distributor_id` 而同一手机号在多家店在册时按**最小 `salesman_id`** 回落（多店是常态，见 C182） | e2e 用例 1/2；冒烟「三种失败同一句 401（停用/没设口令/口令不符）」「`distributor_id` 传错店等于查无此人」 |
| C173 | **员工面一条 `@RequiresResource` 都没有**：45/46 两个码只属于运营面（"管人"是后台动作），员工能看见什么、能改什么全部由会话里的 `salesman_id`/`distributor_id` 决定，**不接受前端传值覆盖**——`SO-E1` 入参给 `salesman_id` 一律忽略，`ST-E5` 明细强制 `salesman_id=会话` | e2e 用例 10/12；冒烟「ST-E5 入参 `salesman_id=1` 被会话覆盖」「SO-E1 `salesman_id` 取会话（入参传谁都忽略）」 |
| C174 | **这条链的位次就是这条链的全部难点**：`staffChain` 必须 `@Order(0)`。`/api/admin/**` 是 `/api/**` 的子集，排在 `adminChain` 之后它永远轮不到，员工令牌会掉进运营链被 `JwtOperatorFilter` 认成"不是 OPERATOR" → **回的是 401，与"链没匹配上"同形**，排查极费时间。规范头是 `x-wxapp-session`，`Authorization: Bearer` 同值兼容（D44）；只有 `/api/admin/wxapp/login` 匿名，其余每条都要 `Kind.SALESMAN` 会话 | e2e 用例 1「令牌就绪与三条链互不认对方」；冒烟第 1 段（员工令牌打 `/api/salesmans` 401、运营/会员令牌打 `/api/admin/wxapp/getinfo` 401、两种头都认） |
| C175 | **口令只进不出**：`password` 只在建/改时接收，`SM-A1/A2/A3`、`ST-E2`、前台 `SM-F1` 的出参里没有这一列（bcrypt 落库，明文只出现在 seed 的哈希与演示脚本的拼接串）。`SM-A3` 是三态读-改-写：不传=不动、**传空串=清空口令**（清空后即刻 401，与口令不符同一句：`hash.isNullOrEmpty()` 排在比对之前）、传新串=改 | e2e 用例 6「建人校验与口令只进不出」+ 用例 7；冒烟「传空串=清空口令，随即 401」 |
| C176 | **岗侧提现是一口池上的 `min`，不是第二份钱**：`可提 = min(本岗已结算 − 本岗占用, 店池余额)`。岗侧聚合（`sumSalesman` / 岗侧占用）与店侧同一套 `CASE`，只是键换成 `salesman_id`——**故意写成第二条 SQL 而不给 `sum` 加可选参数**：两侧鉴权来源完全不同（店按 `DistributorScope`，岗按在册行），合并成一条可选参数就是把两份账并成一份错账的路径。记录面分栏：`CW-F2` 只出 `salesman_id=0`，`CW-F4` 只出 `>0`，后台 `CW-A1` 可按 `salesman_id` 筛 | e2e 用例 14/15；冒烟「C176 一口池取 min」「CW-F2 只出店侧的行」「CW-F4 只出岗侧的行」「CW-A1 按岗筛」 |
| C177 | **归属在落单那一刻定死**：员工代客下单由会话强制注入；会员自己下单回落到 `members.salesman_id`（`form.salesmanId?.takeIf { it > 0 } ?: members.salesmanIdOf(userId, companyId) ?: 0`）。此后换绑、停用、改岗都**不回改**历史单与已落流水（与 D46 同一条纪律）；`distribute_logs.salesman_id` 从订单头带下来，入账时不重新查绑定 | e2e 用例 9「绑定导购后自单自动带归属」；冒烟「绑上之后会员自己下单 `salesman_id` 跟着进单」「落单即定（换绑不回改旧单）」 |
| C178 | **"在册"是三处共用的同一个判据**（`salesman.is_valid`），停用即刻三处生效：员工面每个请求回查 → 401（D44，已有会话也立刻作废）；前台 `SM-F1` 查无此人 → 404；钱面 `CW-F3` 断钱。三处读的都是**在册行**，不是 `members.salesman_id` 那个当前绑定 | e2e 用例 7「改档调岗启停与停用即刻生效」；冒烟「停用即刻关掉已有会话」「停用后前台也查无此人」「岗停用后 CW-F3 立刻断钱」 |
| C179 | **`CW-F3` 的错误形状分三支**：① 一家岗都没有 → **403**「您不是任何店铺的在册导购」；② 还有别的岗、只是本店没有 → **422** + `errors.distributor_id=["无此岗"]`；③ 未传 `distributor_id` 且在 ≥2 家有岗 → **422**「您在 N 家店铺各有导购岗」+ `["必填"]`。资格判据（①②③）一律排在额度判据（"可提现 X 分"）之前，与 C153 同一条纪律 | e2e 用例 14「提现先认在册岗再认余额与在途」；冒烟「没岗位的会员以导购身份提现 → 403」「指明一家他没岗的店 → 422 `["无此岗"]`」「驳回后岗侧在途清了，但人已停用 → 仍 422」 |
| C180 | **一口池只有一个在途位**（本批实跑照出来的口径，写实现时没料到）：店侧 `CW-F1` 那道闸门 `countInFlight` 按 `distributor_id` 数行，而岗侧那笔**同样带 `distributor_id`** → 岗挂一笔在途时店侧申请直接 422；反过来店侧把池提空后，岗侧不是被闸门挡、是被 `min` 压成「可提现 0 分」。两种挡法共用一条理由：**别让两笔申请同时盯着同一份钱**，否则财务按行打款必然有一笔超付。岗侧那道 `countInFlightBySalesman` 才是真按岗挡（同店两个导购各提各的互不影响） | 冒烟「反过来也挡：岗侧那笔同样占住店侧那道在途闸门」「C176 一口池的正证：池被店侧提空后岗侧一分都提不了」「在途唯一按岗挡」；e2e 用例 15「一份池子两个口径且记录分两栏」 |
| C181 | **岗位的可选性 = 归属 + 营业态两条**（`SM-A6`）：`distributor_id=0` 是平台通用岗，每家店建导购时都选得到，但**只有 `admin` 型建得了**（D57 同一条权力划分）；本店专属岗跨店选 → 422「该岗位不存在或已停用」+ `errors.role_id=["本店不可选"]`；`status=0` 的岗同样不可选；删岗前数在册人数 → 422「该岗位下还有 N 名导购，请先调岗再删」+ `errors.role_id=["仍被引用"]`；同店同名岗 422 | e2e 用例 8「岗位 CRUD 走复数路径且删不掉还有人的岗」；冒烟「平台通用岗跨店选得到」「别家店专属岗 → 422 `["本店不可选"]`」「挂着导购的岗删不掉」 |
| C182 | **手机号是店内唯一，不是全局唯一**：`uk_salesman_mobile(distributor_id, mobile)`。同一个人可以在两家店各有一份岗——这正是 C172 多店回落与 C179 ③「指明提现到哪家」存在的前提；同店重号 → 422「该店铺下已有同手机号的导购」+ `errors.mobile=["店内重复"]`。常驻演示库里这条是真索引，脚本侧因此必须按时间戳生成手机号、收尾**停用**本轮岗而不是删行（删行会把别家店的历史引用一起抹掉） | 冒烟第 3 段「手机号店内唯一」「本轮建的四个岗全部停用（残留的人登不进员工端，也不占下一轮的唯一索引）」 |
| C183 | **员工代客下单只有"给一个会员开单"这条路走得通**：给了 `user_id` → 走那个会员的车内结算；被代的人不存在或跨租户一律 **404**（不给"这个 `user_id` 存在吗"），越店 → **403** `errors.distributor_id=["非本店铺"]`。员工本人若是没绑账号的散客（`user_id=0`），购物车路径自然算出 0 行 → 422「购物车为空」，与前台同一条判据、**不是缺陷**；`fastbuy`/`offline` 这种"带着行来"的模式照样成交 | e2e 用例 10「代客下单三条强制」；冒烟「越店开单 403 `["非本店铺"]`」「被代下单的会员不存在 → 404」「散客走购物车 → 422（期望行为不是缺陷）」 |

### 22.2 与源系统的差异（D 系列续编）

| # | 变更 | 理由/缓解 |
|---|---|---|
| D63 | **店侧提现也必须有在册行**：`CW-F1` 的归属判据是 `salesmen.countActive(companyId, userId, distributorId)`，不是"这家店的老板"——本项目 `distributor` 表根本没有老板列，源系统靠 `distributor.user_id` 那一条。代价：从没配过导购的店主提不了现。缓解：seed 给店 2 的两条在册行绑了 `user_id=1/2` 并设了演示口令，演示库开箱能提 | §18.5 的 CW-F1 一句话在源系统里是"老板提自己店的钱"，本项目改写成"在册员工替店提"，归属只有一条可信来源。验证：冒烟「同一个人替店提也要有在册行」 |
| D64 | **`role_id=0` = 没配岗，允许存在但不接待顾客**：`SM-A2` 建人时可以不选岗（源系统同形），但 `SM-F1` 扫码面把 `roleId<=0` 的人当查无此人 404。seed 的 `salesman_id=1`（demo 旗舰店老板）正是 `role_id=0`，只用来走店侧提现 | 没岗的人没有可展示的角色，前台给他挂一个"未知岗位"比不出现更容易误导顾客。验证：e2e 用例 4「扫码进店查得到有岗的导购查不到没岗的」 |
| D65 | **员工令牌只有 `salesman_id / distributor_id / company_id` 三 claim**（`Kind.SALESMAN`），不还原 PHP 的服务端会话（D44）；每个请求回查 `is_valid`。代价是每请求一次索引查询，换来"停用真的停用" | 源系统停用导购后旧会话仍活，本项目不继承。验证：冒烟「停用即刻关掉已有会话（D44）」 |
| D66 | **`SO-E1` 复用 `ORDER-2` 那条建单入口**（给 `OrderForm` 开一个 `userId` 口子），不写第二条下单链：前台的 `user_id` 永远取自令牌，只有员工面才接受入参 `user_id`。两条链迟早要在算价、扣库存、佣金入账、留痕四个地方各漂一个版本 | 代价是 form 上多一个"只对员工有意义"的字段，注释里写明了谁能用。验证：e2e 用例 10 + 冒烟第 6 段整条 |
| D67 | **岗侧聚合是新增的第二条 SQL**（`sumSalesman` 与岗侧占用），不是给店侧那条加可选参数（C176 的结构性理由）。看起来重复、审起来分得清 | 验证：e2e 用例 15 分别断两侧占用与余额；冒烟「岗的余额被自己的在途提现扣光」「`withdraw_occupying` 含岗侧申请」 |


## 23. P1 端点规格 —— 积分 / 储值 / 评价 / 发票（M1-5 开发前补齐，追加于 §22 之后，不回改上文）

盘点结论（本轮只读调研得出，不是推断）：**账已经有一半**。`point_member` / `point_member_log` / `user_deposit` / `deposit_trade` / `deposit_recharge_rule` 五张表与它们的 CAS、流水写入、充值结清（`PayService.settleRecharge`、`PointChannel`、`DepositChannel`）在 M0-7/M0-8 就落地了；`trade_rate`、`trade_rate_reply`、`orders_invoice`、`members_invoices` 四张表**只有表、代码零引用**（全仓 `.kt` 里搜不到这两个类名与这两个表名）。真正的缺口有四处，都已实测：

1. 积分与储值**没有 HTTP 读口**——流水只写不查，会员看不到自己的积分，后台对账只能连库；
2. `orders_normal_orders.get_points` **建单时从不赋值**（列在 INSERT 里，但全仓没有一处 `getPoints =`；`PayEndToEndTest` 是靠手写 `UPDATE ... SET get_points = 100` 才让 C54 那条断言跑绿的）。行侧反倒是算好的：建单时 `itemPoint = (item?.point ?: 0L) * num` 已落库。所以 C54「支付赠分入账」这条判据从未真跑通过——线上结清的订单赠分恒等于 0；
3. `is_rate` **只有读没有写**（`OrdersMapper` 的分桶 SQL 在算 `DONE AND is_rate = FALSE`，但没有一个写口把它翻成 true），ORDER-8 的 `evaluation` 桶因此恒等于"已完成数"；`invoice_status` / `is_invoiced` 同样只读不写；
4. 脱敏在六处各写了一遍（`MemberService.mask`、`CommissionService.mask`、`SalesmanService.mask`、`PickupLocationService.maskPhone`、`GroupsService.maskMobile`、`AftersalesService.mask`）——源系统那层 `datapass` 中间件在本项目没有对应物。

### 23.1 前台（前缀 `/api/h5app/wxapp`，鉴权=会员 JWT；`user_id` 一律取自令牌）

| ID | 方法 | 路径 | 说明 |
|---|---|---|---|
| PT-F1 | GET | `/point` | 我的积分总览：`{point, total_point, user_point, ratio, max_deduct_fee, enable}`（`ratio` 读 `sys_config.point.deduct.max_ratio`，与 ORDER-1 预览的 `max_point`/`limit_point` 同一口径 C117；`enable` 读 `pay.point.enable`） |
| PT-F2 | GET | `/point_logs` | 积分流水分页（`page,pageSize,remark?`）：`{list[]{id,order_id,change_point,before_point,after_point,remark,created}, total_count}`；只出本人 |
| PT-F3 | GET | `/point_rule` | 积分规则展示：`{enable, deduct_max_ratio, point_per_fen, get_by_item:true, text}`。**赠送没有全局倍率**——"这一行得几分"是商品自己标的 `items.point`（建单已按 `item_point` 落库），本条只把开关、抵扣上限与"1 积分 = 1 分"（`PayChannel.POINT_PER_FEN`）拼成文案，库里不存文案 |
| CZ-F1 | GET | `/deposit` | 我的余额：`{deposit_amount, gift_amount, balance, total_recharge, enable}`；`balance = deposit_amount + gift_amount`（**赠金也是钱**，只在前端分栏显示）；`enable` 读 `pay.deposit.enable` |
| CZ-F2 | GET | `/deposit_logs` | 储值流水分页（`trade_type?` recharge/consume/refund）：`{list[]{trade_id,trade_type,rel_id,amount,gift_amount,before_balance,after_balance,status,created}, total_count}`；`NOTPAY` 的 CZ 单也在内（他还在等支付） |
| CZ-F3 | GET | `/deposit_rules` | 充值档位（读 `deposit_recharge_rule` 且 `status=1`，按 `recharge_amount` 升序）：`{list[]{id,rule_name,recharge_amount,gift_amount}}`；无档位时回 `[]`，前台自己决定要不要放开自定义金额 |
| RATE-F1 | POST | `/order/rate` | 提交评价：`{order_id(Y), items[](Y)}`，`items[]` 每行 `{item_id(Y), star(Y 1..5), content, rate_pic[], anonymous}`；一单一提交、按行落 `trade_rate`，出 `{order_id, rate_count, is_rate:"true"}` |
| RATE-F2 | GET | `/order/rate/list` | 评价列表：`item_id(Y)` 或 `distributor_id?`，`page,pageSize,star?,has_pic?`；出参含 `seller_reply{content,created}`（一行的最新商家回复），**匿名评价的行 `nickname` 回 `匿名用户`、`mobile` 不回** |
| RATE-F3 | GET | `/order/rate/{rate_id}` | 评价详情：单行 + `replies[]{role,content,created}`（`role` 只有 `seller`/`buyer`，买家追评与商家回复同表共存，按 `created` 排序） |
| RATE-F4 | GET | `/order/rate/my` | 我的评价（`page,pageSize`）；与 ORDER-4 的 `status=7` 待评价页签互为补集（页签数 + 本页行数 = 已完成单数） |
| RATE-F5 | GET | `/order/rate/pending` | 待评价行清单：`{order_id, item_id, goods_id, goods_name, pics, spec_desc, num}`——评价要按行提交，前台必须先拿到"这一单还有哪几行没评"（**已完成但 `is_rate=false` 的行**，不是整单） |
| INV-F1 | POST | `/order/invoice/apply` | 申请/修改发票：`{order_id(Y), invoices_id?, invoice_type(Y enterprise/individual), invoice_type_code?, company_title, tax_number?, email(Y), mobile?, remark?}`；带 `invoices_id` 时从抬头本带出抬头（只接受本人的，越权 404）；`pending` 态可反复改（**upsert，不是插新行**），`issued` 后改 → 422 |
| INV-F2 | GET | `/order/invoice` | 我的申请列表（`status?`，分页）：`list[]{id,invoice_apply_bn,order_id,invoice_type,company_title,invoice_status,invoice_amount,create_time}` |
| INV-F3 | GET | `/order/invoice/{id}` | 详情（含 `invoice_file_url`；非本人与不存在同样 404） |
| INV-F4 | GET | `/member/invoices` | 抬头本列表（`members_invoices`，只出本人）：`list[]{invoices_id,invoices_type,name,telephone,tax_number,business_address,bank,bank_account,is_def}` |
| INV-F5 | POST | `/member/invoice` | 新增/修改抬头：`invoices_id?` 有则改；`invoices_type=corporate` 必填 `tax_number`（`personal` 不填）；置 `is_def=true` 时把该会员其余行的 `is_def` 清零——**默认抬头只有一个**，两个 true 的前台没法选 |
| INV-F6 | DELETE | `/member/invoice/{id}` | 删除抬头；被 `orders_invoice` 引用过的行**不删**（历史发票的抬头必须留得住），回 422 + `errors.invoices_id=["已被发票申请引用"]` |

### 23.2 后台（前缀 `/api`，鉴权=运营 JWT + `@RequiresResource`，写路径 `@Audited`）

| ID | 方法 | 路径 | 说明 |
|---|---|---|---|
| PT-A1 | GET | `/point/members` | 会员积分列表（`keyword?`/`page`/`pageSize`，datapass 掩码）：行 `{user_id, nickname, mobile_masked, point, total_point, updated}` + 卡头 `{total_point_sum, total_granted, total_used, member_count}` |
| PT-A2 | POST | `/point/adjustment` | 后台调账：`{user_id(Y), change_point(Y ≠0，正负皆可), remark(Y)}`；写 `point_member_log` 并 CAS 加，**负数钳到 0**（不允许把账调成负），出 `{user_id, before_point, after_point, change_point}`；`remark` 必填的理由是这条是唯一"无订单来源"的动账口，对账脚本要靠它解释差额 |
| PT-A3 | GET | `/point/logs` | 全量流水（`user_id?`/`order_id?`/`time_start_begin?`/`time_start_end?`）；`Σchange_point == point` 的对账口径以这一条 + PT-A1 为可跑面 |
| PT-A4 | GET/PUT | `/member/point/rule` | 规则读写（落 `sys_config`，见 23.4）：GET 回当前值与默认值，PUT 校验区间后整组写回；`point.deduct.max_ratio` 越界（0..100 之外）→ 422 |
| CZ-A1 | GET | `/deposit/trades` | 储值交易列表（`trade_type?`/`status?`/`user_id?`/时间段，datapass）+ `total_count` |
| CZ-A2 | GET/POST/PUT/DELETE | `/deposit/recharge_rules`（列表）、`/deposit/recharge_rule`（新建）、`/deposit/recharge_rule/{id}`（改/删） | 档位 CRUD：`recharge_amount>0`、`gift_amount>=0`；删除不设守卫（档位是价目表不是账，历史 `deposit_trade` 已把当时金额写进自己那行） |
| CZ-A3 | POST | `/deposit/recharge` | 后台代客充值：`{user_id(Y), amount(Y>0 分), gift_amount?, remark(Y)}`；**不走支付**，当场落一条 `deposit_trade(trade_type=recharge, status=SUCCESS)` 并入账，出 `{trade_id, deposit_amount, gift_amount, balance}` |
| CZ-A4 | GET | `/deposit/count` | 储值总览：`{principal_sum, gift_sum, notpay_count, recharge_sum_7d}`（`principal_sum` 读 `user_deposit` 现值、`recharge_sum_7d` 读 `deposit_trade`，两个数各说一件事、不拿一个去减另一个） |
| RATE-A1 | GET | `/comments` | 评价列表（`order_id?`/`item_id?`/`star?`/`is_reply?`/`distributor_id?`）：行带 `nickname/mobile_masked`、`shop_name`（JOIN）、`seller_reply` |
| RATE-A2 | POST | `/comment/reply` | 商家回复：`{rate_id(Y), content(Y 非空)}` → `trade_rate_reply(role='seller')` + `trade_rate.is_reply=true`；**重复回复=覆盖最新**（口径 RATE-R4） |
| RATE-A3 | PUT | `/comment/{rate_id}/disable` | 上下架：`disabled` 位翻转（源系统叫"屏蔽"）。**只影响 `RATE-F2` 的可见集，不删行、不改 `is_rate`**——评价撤回后订单仍算已评价，否则用户可以无限次重评 |
| INV-A1 | GET | `/order/invoices` | 发票申请列表（`invoice_status?`/`order_id?`/`keyword?`，分页） |
| INV-A2 | PUT | `/order/invoice/{id}` | 处理：`invoice_status` 迁 `pending → issued/rejected`，`issued` 必须带 `invoice_file_url` 或 `email`（二者皆空 → 422，"开了票但会员什么都收不到"不接受）；`rejected` 必须带 `remark` |
| INV-A3 | GET | `/order/invoice/{id}` | 详情（后台看到未掩码的 `tax_number`/`email`，与列表面同一权限码，见 23.3 第 12 条 MASK-R1） |

### 23.3 关键口径（开发前定死，避免实现期各写一套）

1. **PT-R1 补 `get_points` 的建单写入，行侧不动 schema**：建单时 `getPoints = lines.sumOf { it.itemPoint }`（行表 `orders_normal_orders_items` 只有 `item_point`，没有 `get_points` 列；`OrderQueryService` 里那处行出参 `"get_points" to 0L` 是从契约里补出来的空位，本批保持不动并在出参注释里写明"行侧得分读 `item_point`"）。入账仍只在 `PayService` 的结清点（C54），`point_member_log.order_id` 是幂等判据（已有行就跳过），补单作业复用 `PayService.notify` 因此天然不双计（C104）。这条落地后 `PayEndToEndTest` 里那句手写 `UPDATE` 必须删掉——留着就是把"判据没跑通"伪装成"测试通过"。
2. **PT-R2 抵扣、退款、回补三种"往回加"只动余额不动 `total_point`**（C120 的纪律在积分域之外同样成立）：`total_point` 只表示"获得过多少"，动它就等于动等级与榜单。
3. **CZ-R1 赠金与本金在账上分列、在支付面上合并**：`DepositChannel` 扣款顺序固定"先赠金后本金"，`deposit_trade.amount` 记总动账、`gift_amount` 记其中赠金部分；后台代客充值（CZ-A3）走同一个充值入账腿，不开第二条入账路径。
4. **RATE-R1 一单一提交、按行落、`is_rate` 用 CAS**：`UPDATE orders_normal_orders SET is_rate = true WHERE order_id = ? AND is_rate = false` 回 0 行就是重复提交 → 422「该订单已评价」。闸门顺序＝先认单（非本人 404）→ 再认状态（非 `DONE` 422）→ 再认 `items[]` 与订单行的对应关系（给了不在这一单里的 `item_id` → 422，**不静默丢弃**）→ 最后才 CAS。
5. **RATE-R2 `star` 是唯一的必填数值**，`content` 可空（源系统允许空内容评价，本项目不收紧）；`rate_pic` 存 JSON 数组字符串（与 `items.pics` 同一惯例），出参用 `jsonList` 解析、脏值回空集而不是把整行带崩（C137 的教训）。
6. **RATE-R3 匿名只在会员面对非本人隐藏身份**：`RATE-F2` 里 `anonymous=true` 的行对别人回 `匿名用户`、**对自己回真名**；`RATE-A1` 后台面永远不匿名（datapass 只掩码手机号，不隐藏"这是谁"）。
7. **RATE-R4 商家回复一行一条最新**：`is_reply` 是"有没有回复过"的位，重复回复覆盖 `trade_rate_reply` 里 `role='seller'` 的那条（买家追评 `role='buyer'` 可多条）；`RATE-F2/F3` 的 `seller_reply` 读这一条。
8. **INV-R1 一单一申请单**：`orders_invoice.order_id` 在源系统是"逗号分隔多订单"，本项目**收紧为单个订单号**并加 `uk_invoice_order(company_id, order_id)`——理由是发票要按单开票、`invoice_amount` 只能对着一个订单算。多单合开属 P2（放开时去掉 uk、`invoice_amount` 改按 `order_id` 列表求和）。`pending` 态 upsert，`issued/rejected` 是终态、改 → 422。
9. **INV-R2 申请即置订单侧的位**：成功后 `orders.is_invoiced=true`、`invoice_status='pending'`、`orders.invoice` 存当时的申请快照（现有 CLOB 列）；后台 `INV-A2` 只回写 `invoice_status`，不回写快照——会员看到的"我申请时填了什么"必须是那一刻的内容。
10. **INV-R3 两套抬头词表不合并，在 Service 层映射**：`members_invoices.invoices_type` 现值域是 `personal/corporate`，`orders_invoice.invoice_type` 是 `enterprise/individual`，两张表的列都已有语义（改值域等于迁移历史数据）。映射固定为 `corporate ↔ enterprise`、`personal ↔ individual`，**对外只暴露各自列的词表**（抬头本接口收 `personal/corporate`，申请接口收 `enterprise/individual`），文档与错误信息里不混用。
11. **BUCKET-R1 `evaluation` 桶自此才真**：ORDER-8 的 `evaluation = DONE 且 is_rate=false`（C43 的不变量"分桶互斥且求和恒等 total"必须继续成立，e2e 与冒烟都断这条），评价写口落地的同一批要回归 `m07/m11/m13` 三脚本里的积分与角标断言。
12. **MASK-R1 脱敏是渲染层的事，判据层永远用原值**：`datapass` 的裁定见 23.4 末条（MASK-R1）——后台列表默认掩码，只有拿到 `datapass.pass`（63）的账号看全值；掩码只作用于**出参字符串**，筛选、排序、对账一律读原列。六处手写掩码在本批收敛成 common 的一个 `Mask`（位数、`***` 位置、空值行为以 `MemberService.mask` 现状为准，回归由现有十四脚本承担）。

### 23.4 数据与配置追加（写实现前先定形状，全部幂等）

- `ALTER TABLE trade_rate ADD COLUMN IF NOT EXISTS distributor_id BIGINT NOT NULL DEFAULT 0` + `shop_id BIGINT NOT NULL DEFAULT 0`——评价必须能按店铺收窄（加盟体系里"这条差评是谁家的"是运营的第一筛），表原本只有 `goods_id/item_id`；落评价时从订单行的归属带下来，与 `distribute_logs.salesman_id` 同一条"归属在落单那刻定死"（C177）。
- `ALTER TABLE trade_rate ADD COLUMN IF NOT EXISTS nickname VARCHAR(64)`（匿名与改名后仍能看到"当时那个名字"，出参位固定存在）。
- `ALTER TABLE orders_invoice ADD COLUMN IF NOT EXISTS remark VARCHAR(255)`（后台处理备注，`rejected` 必填那条判据读它）。
- 索引：`CREATE UNIQUE INDEX IF NOT EXISTS uk_invoice_order ON orders_invoice(company_id, order_id)`；`CREATE INDEX IF NOT EXISTS idx_rate_company_item ON trade_rate(company_id, item_id, created)`、`idx_rate_order ON trade_rate(company_id, order_id)`。**注意 `idx_rate_item(item_id, star)` 已存在**，新索引必须另起名，别指望 `IF NOT EXISTS` 帮你改同名索引。
- 主键名以库为准，实现期别按惯例猜：`trade_rate.rate_id`、`trade_rate_reply.reply_id`、`orders_invoice.id`、`members_invoices.invoices_id`、`point_member.id`、`deposit_trade.trade_id`（字符串号）。时间列同样分两族：`trade_rate`/`point_member_log` 用 `created`，`orders_invoice` 用 `create_time`/`update_time`。
- 发号：`SeqName`（common/domain/Enums.kt，现 8 个位）追加 `INVOICE_APPLY_BN("invoice_apply_bn", "FP")`，schema 同批补一行 `INSERT IGNORE INTO seq_generator (seq_name, current_value) VALUES ('invoice_apply_bn', 0)`。单号形状**沿用 `RECHARGE_NO` 那条**（前缀 + 时间 + 号段尾），不另造一种——`ziti_code` 那种"取末 6 位"是取货码专用的短码，别照搬到申请单号上。schema 必须 `INSERT IGNORE`：缺行时 `SeqGenerator` 抛的是 500（C166 记过）。
- `sys_config` 追加（seed 的 `INSERT IGNORE`，值用现库的 `'1'/'0'` 词表，不要写成 `true`）：新增 `deposit.recharge.point.rate`=`0`（充值送积分倍数，默认关）、`invoice.enable`=`1`、`invoice.types`=`["02"]`（默认只开普票）。**已有三键不动、不另起同义键**：`pay.point.enable`、`pay.deposit.enable`、`point.deduct.max_ratio`（现值 50）。
- 权限码从 **54** 起（seed 现最大 `resource_id` = 53）：`54 point.lists`、`55 point.update`（含 PT-A2/PT-A4）、`56 deposit.lists`、`57 deposit.rule.update`、`58 deposit.recharge`、`59 rate.lists`、`60 rate.reply`、`61 invoice.lists`、`62 invoice.update`、`63 datapass.pass`。role 1（超级管理员）全配；role 2（店铺运营，`distributor01`，现配 6 条码）只加 `54/56/59/61` 四个看数码 + `60 rate.reply`（本店单可回复），**不配** `55/57/58/62/63`——调账、改档位、代客充值、开票、看全值都是平台权力（与 D57/C167 同一条划分）。
- `datapass.pass`（63）是本项目对源系统 `datapass` 中间件的裁定：它**不参与归属判定**，只决定同一行的敏感列出全值还是掩码值；`operator_type=admin` 默认穿透（独立部署形态），其余类型未配 63 一律掩码。归属仍然只由 `distributor_ids` 与数据权限那套判（C145/C146/C167 不因本条改变）。

### 23.5 门槛用例（进 e2e 与冒烟，任一条红就不算交付）

1. **账实相符（docs/05 §72 的门槛）**：对起始余额已知的会员，`Σ point_member_log.change_point == point_member.point` 恒成立；`Σ(成功动账) == user_deposit.deposit_amount + gift_amount` 恒成立。四段动作各测一次：结清得积分、下单抵扣、取消回补、退款扣回；储值测充值、消费、代客充值、退款。**同一份判据写成 e2e 用例与 `tools/m15_reconcile_smoke.py` 的对账段**（实跑面读 HTTP，内存面读 SQL）。
2. **赠分这条要能被证伪**：结清一单后断 `point_member.point` 增加额 == 该单 `Σ item_point`，且**脚本里不许出现手写 `UPDATE ... get_points`**——本批的交付物之一就是让 C54 在没有 SQL 补丁的情况下自己跑通。
3. **越权与状态闸门**：A 会员给 B 会员的单提交评价 → **404**（不是 403，不给"这一单存在"）；对 `NOTPAY`/未完成的单提交 → 422；二次提交 → 422 且库里行数不变；店铺运营账号（role 2）在 `RATE-A1` 上只出 `distributor_id=本店` 的行、`RATE-A2` 回别人店的评 → **404**；`point.update`/`deposit.recharge` 对 role 2 一律 403（权限码判在归属之前，C167）；未配 `datapass.pass` 的账号看 PT-A1/CZ-A1/RATE-A1 的手机号是掩码、配了的是原值——**同一行、两个账号、两个出参**。
4. **抬头与发票的形状**：`personal` 抬头不填 `tax_number` 也能过、`corporate` 不填 → 422；两个抬头同时 `is_def=true` 测不出（写入即清零其余），断库里 `SUM(is_def)=1`；`issued` 后再 `INV-F1` 改 → 422；同一 `order_id` 二次申请 → 走 upsert 而非插行（断 `COUNT(*)=1`）；被引用过的抬头 `INV-F6` → 422。


## 24. 实现期语义补充（M1-5 #72..#75：积分 / 储值 / 评价 / 发票落地时确认，追加于 §23 之后，不回改上文）

交付面：`PT-F1..F3` + `CZ-F1..F3` + `RATE-F1..F5` + `INV-F1..F6` 共 **17 条前台**、`PT-A1..A4` + `CZ-A1..A4` + `RATE-A1..A3` + `INV-A1..A3` 共 **14 条后台**（31 条规格行 = **35 个 handler 方法**：`PT-A4` 的 GET/PUT、`CZ-A2` 的档位四条、`INV-A2/A3` 共用 `/api/order/invoice/{id}` 各按方法计）。权限码 **54..63** 十码 + 目录码 **64** `member_assets` 落 `seed`（role 1 全配、role 2 只 `54/56/59/61` 四个看数码 + `60 rate.reply`）。新代码落位：`dao/rating/RateMappers.kt`、`dao/invoice/InvoiceMappers.kt`、`service/asset/{PointService,DepositService,AssetSupport}.kt`、`service/rating/{RateForms,RateService}.kt`、`service/invoice/{InvoiceForms,InvoiceService}.kt`、`service/auth/Datapass.kt`、`web-front/{Asset,Rate,Invoice}FrontController.kt`、`web-admin/{Point,Deposit,Rate,Invoice}AdminController.kt`；改动面：`OrderCreateService`（建单算 `get_points`）、`PayService`（结清点赠分入账）、`PayChannel`（抵扣腿留负流水）、`PayMappers`（`PT_LOG_WHERE`/`PT_ACCOUNT_WHERE` 两本字典 + `usedSum` 符号）、`OrdersMapper`（`evaluation` 桶自此才真）、`Enums.kt`（`SeqName.INVOICE_APPLY_BN`）、`Mask.kt`（六处手写掩码收敛 + 新增 `taxNo`/`email`/`title` 三支）、`sql/schema.sql`（§23.4 那批追加之外，实现期又补了 `orders_invoice.invoices_id` + `idx_invoice_head`、`deposit_trade.remark`）、`sql/seed.sql`（十码 + 目录码 64 + 四条 `sys_config` + `invoice_apply_bn` 号段行 + 两条演示抬头；两条充值档是 M0-7 就有的，本批只读不改）。

### 24.1 口径确认（C 系列续编）

| # | 口径 | 实测依据 |
|---|---|---|
| C184 | **账先有读口才算能管**：`PT-F1..F3` / `CZ-F1..F3` 六条全是 GET、一处写口都没有（写仍在 `PayService` 的结清点与 `PointChannel`/`DepositChannel` 里），`user_id` 一律取自令牌**不接受入参**，所以"读到别人的账"在这六条上不可能成立；`pay.point.enable`/`pay.deposit.enable` 各自读自己的开关，**关掉时余额数字照出、`enable` 位负责让前台收起入口**——余额是既成事实，不该被一个开关抹掉 | e2e 用例 2「六条读口全在会员链上：没令牌就是 401」；冒烟「PT-F1 六键齐」「CZ-F1 balance 就是本金 + 赠金两栏」 |
| C185 | **常驻库上恒等式只能按差值断**：`point_member` 的 seed 给了 1000 分却配不出对应流水（`point_member_log` 无唯一键，`INSERT IGNORE` 在常驻库上会每启一次多插一份，所以本批**没有**给它配种子行），于是判据写成 `point − point₀ == Σ − Σ₀`；储值侧 seed 是 0/0，因此可以直接按绝对式 `Σ(成功动账 ±) == deposit_amount + gift_amount` | 冒烟「进场基线：积分 1000 / 流水和 0 / 余额 0 / 动账和 0」与其后每一段的两条 `Δ…` 断言（共 12 组，每动一次账过一遍）；e2e 用例 1 记基线、每段 `assertLedgerBalanced` |
| C186 | **抵扣这条腿自此留痕**：三条 `remark` 字面量定死——`订单积分抵现`（建单抵扣）、`积分支付抵扣`（纯积分兑换）、`订单支付赠送积分`（结清赠送），关单回补另写一条 `订单关闭，积分抵现回补`。副作用是一条积分支付单**从 1 行流水变成 2 行**（扣 8900 + 赠 100），`PayEndToEndTest` 里那条 `assertEquals(1, log.size)` 是本批照出的陈旧断言、不是回归 | 冒烟「抵扣腿自己留了一条负流水」「扣一条、回一条，两行都在（只留一条就对不上账）」「抵扣与赠送各一条流水，两条合起来等于余额差」；`PayEndToEndTest` 用例 5 现断 2 行与两行各自的四个数值 |
| C187 | **`total_point` 只表示"获得过多少"**：正向调账与结清赠分动它，抵扣、回补、退款一律不动（PT-R2）。而 `user_point`/`total_used` 这两个"累计扣减"**读的是 `Σ(负流水绝对值)`**，不是 `total_point − point`——后者会被回补算没（抵扣 8900 又回补 8900 时两列相等，扣减就显示成 0）。这条口径是写 `usedSum` 那趟照出来的：按直觉先走过一遍"两列一减"，PT-A1 卡头上出现 `-19000` 这种不可能的扣减数，才固定成 SQL 里那句 `AND l.change_point < 0` | 冒烟「PT-F1 的 `user_point` = Σ(负流水绝对值)，不是 total_point - point（回补会被后者算没）」「PT-A1 卡头 `total_used` …把回补算没的那种口径已经修掉了」「抵扣不是获得：这一腿前后 total_point 一分不动」「回补不动 total_point：回补不是获得」；e2e 用例 4 三个合计数与 SQL 逐值对 |
| C188 | **赠分由建单写入，行侧不另开列**：`orders.get_points` = `Σ 订单行 item_point`（§23.3 PT-R1），实现期没给行表加"这一行得几分"的列；后台 `ORDER-9` 的 `items[].get_points` 因此是**契约补位的 0**，得分读 `items[].item_point`。门槛②"能证伪"靠的就是这条：脚本里连一句 `UPDATE` 都写不出来（没有 SQL 通道），结清后余额增加额只能来自建单算出的那个数 | 冒烟「PT-R1：单头 get_points 由建单算出，且 = Σ行 item_point（本脚本无 SQL 通道，只能是建单写的）」「行侧不另开得分数列…」「赠送分的 remark 与金额（C54 那条判据在没有 SQL 补丁的情况下自己跑通）」；e2e 用例 5/6 |
| C189 | **`deposit_trade` 的 `amount` 是本金腿、`gift_amount` 是赠金腿**（§23.4 出参注释里"`amount` 是总动账"那句以库与实现为准：两栏相加才是这一腿的总额），方向由 `trade_type` 定（`recharge`/`refund` 进、`consume` 出）；`NOTPAY` 的充值单照样出现在 `CZ-F2` 里（他还在那一步等支付），但**不进恒等式右侧**——钱没进来就不能算进来 | 冒烟「PAY-4 充值单没结清之前钱不进余额」「CZ-A1 充值行：amount 是本金腿、gift_amount 是赠金腿」；e2e 用例 3 第一段两条 |
| C190 | **消费先扣赠金、退款按分栏原路回补**：赠金优先扣完、本金兜底剩下的部分，`consume` 那一行把两条腿分别记下来，退款才拆得开——赠金**不折成本金**（那等于把送的钱又送一遍）。所以 `deposit_logs?trade_type=refund` 那行的 `amount + gift_amount` 必须等于当初消费那一行的合计 | 冒烟「余额消费：先赠金后本金，两栏各自动」「赠金优先扣完：这一行的 gift_amount 不超过账户赠金」「退款原路回补，赠金不折成本金」「退款行与消费行同名单号（rel_id 指得回同一张单）」 |
| C191 | **400 / 422 / 404 三支分工**：入参形状（必填、类型、词表外、脏邮箱、`star` 越界、空 `items`）→ **400** 且带 `errors` 四件套；状态与引用完整性（未付款就申请发票、已 0 分还硬扣、终态改单、被引用的抬头删不掉、未开自提…）→ **422**；越权与不存在**同形 404**，不给"这一单/这个 id 存在"。`CZ-A2` 的零元档回 400 而不是 422，因为 `recharge_amount > 0` 是必填判据不是业务规则 | 冒烟「空 items 走 400 且错误体四件套（不是 422…）」「未付款的单申请 → 422；单不存在 → 404」「被引用过的抬头删不掉：422」「零元档 400」；e2e 用例 4/9/11 |
| C192 | **三族收窄判据各不相同，别当成一条**：评价按**行上两列**（本批 ALTER 的 `trade_rate.distributor_id/shop_id`）筛；积分账（`PT-A1/A3`）与发票账（`INV-A1`）按 **EXISTS：这个会员在我名下下过单**筛（账本行上没有店铺列，硬加一列等于把历史流水改一遍）。因此一个会员只要在我名下有过一单，他在**别家店**的积分流水与发票申请我也读得到——这是 §23.4 的裁定，不是漏判；验证姿势是先造一张店 2 的**已付**单把他拉进集合，再对同一行断"受限面掩码 / admin 面全值" | 冒烟「这个会员一旦被纳入收窄集合，他在店 1 的申请 role 2 也读得到（200）…§23.4 的裁定，不是漏判」「店 2 的单进了 role 2 的收窄集合：PT-A1 因此看得到这个会员（EXISTS 判据在老库上生效）」；e2e 用例 4/11（内存面用 `mobile` 全值 + `mobile_masked` 两列并存证同一件事） |
| C193 | **`datapass.pass` 只决定全值还是掩码**（63 不参与归属判定，`operator_type=admin` 默认穿透），六处手写掩码收敛成 `common/security/Mask.kt` 一支：`mobile` 固定前 3 后 4 中间四位星（**与 `phone` 是两支**：座机按长度补星），本批新增 `taxNo`（留前 4 后 3、中间等长打星）、`email`（只掩本地部分、域名一列字符都不能少）、`title`（留前 2 后 1）。三条都**掩后等长**，且 `mobile_masked` 键恒在——出参形状不随权限变化，前端一套代码渲染两种账号（MASK-R1） | 冒烟「datapass.pass（63）只决定全值还是掩码：同一行、两个账号、两个 mobile」「新掩的 taxNo/email/title 逐字对」「发票行的 mobile 走的是收敛后那一支」「同一行在 admin 面上是全值：三列一个星都没有」「INV-A1 的 keyword 筛抬头名…受限账号一样筛得动」；`MaskTest` 7 例（含空值与短值边界） |
| C194 | **可见性筛必须同时作用在 `count` 与 `page` 上**：`RATE-A1` 默认隐藏下架行、`include_disabled=1` 才全出。本批实现期在这里连错两处——① 那条 `<if>` 只挂在取数 SQL 上，`count` 不管，于是总数算着下架的行、列表却不给，分页器按 `total_count` 发页码、最后一页就成了空页；② 服务层把 `include_disabled` 归一成**布尔**（永远非 null），而 SQL 判的是 `f.includeDisabled == null`，那道筛从未生效、后台默认面连下架的也端出来。现固定为**三态**（`true` / `null`，同 `isReply` 那支）且判据进 `R_FILTER_SQL` 字典与 `f.disabled` 互斥 | 冒烟「下架后后台默认也看不见，带 include_disabled=1 才出（且不删行、不改订单 is_rate）」「total_count 跟着可见性一起减（count 与 page 共用同一道筛，末页不会是空页）」；e2e 用例 8 三条（下架后列表不含它、`total_count` 跟着 −1、带参时两张数一起放开） |
| C195 | **`PT-A4` 三张对照里 `effective` 是解析后的类型**（`pay.point.enable` → 布尔、`point.deduct.max_ratio` → 整数、`deposit.recharge.point.rate` → 浮点），`config`/`defaults` 两张仍是 `sys_config` 的字符串原值。留着 `effective` 的意义就在于"值写得对但解析不出来"这类脏配置能被看见——三张都是字符串时它退化成 `config` 的副本 | 冒烟「PT-A4 的 effective 是解析后的类型（不是 config 的副本）」「PT-A4 的 config/defaults 两张仍是字符串原值」；e2e 用例 4 读写往返（PUT 30 之后三张对照各自跟着变） |
| C196 | **写评价不碰账本、也不改分桶形状**（BUCKET-R1 续）：`evaluation = DONE 且 is_rate=false` 是 `done` 的**子桶**、不参与六桶求和；评价写口只翻 `is_rate`，所以评完整单 `evaluation −1` 而 `done` 不动、`total == notpay+pending_delivery+pending_receive+done+cancel` 恒成立。上下架（`RATE-A3`）**不动 `is_rate`**，撤回后订单仍算已评价，否则同一条可以无限次重评 | 冒烟「BUCKET-R1：六桶互斥且求和恒等 total（evaluation 是 done 的子集，不是第七桶）」「评完整单退出待评价桶：evaluation -1、done 不动」「提交评价不碰账本：赠送分仍只有一条（写口与账口分开）」「上架回来…历史单仍是已评价」；e2e 用例 7/8；m07/m11/m13 三脚本同库回归全绿 |
| C197 | **发票这条链的形状**：`INV-F1` 会员行 17 键恒在（值按 datapass 决定全量还是掩码），后台多 `user_id`/`nickname` 两列；申请单号 `FP` 前缀走 `seq_generator`（缺行是 500 不是 422，C166 那条纪律在老库上复验）；`pending` 态二次申请 = **upsert 同一条**（`uk_invoice_order` 真的建得起来），`issued/rejected` 是终态、再改 → 422；`issued` 必须带 `invoice_file_url` 或 `email`（二者皆空 → 422："开了票会员什么都收不到"），`rejected` 必须带 `remark`（本批新列）；抬头被引用后删不掉，且实现期给 `orders_invoice` 补了 `invoices_id` 反向列——**抬头的历史快照存在申请行上**（改申请不冲掉税号），删除时才需要数引用 | 冒烟「pending 态二次申请 = upsert 同一条」「申请单号 FP 前缀…那行在常驻库上」「issued 但既没文件也没邮箱 → 422」「被引用过的抬头删不掉：422 + invoices_id 一条原因」「INV-R2 申请即置订单侧的位：is_invoiced + invoice_status=pending」「订单侧状态跟着同步（只同步状态，不回写申请快照）」；e2e 用例 12/13 |

### 24.2 与源系统的差异（D 系列续编）

| # | 变更 | 理由/缓解 |
|---|---|---|
| D68 | **`datapass` 不还原成中间件**：源系统是一层 `datapass` 中间件 + VO 上的 `MaskedString`，本项目做成**装配期的一个纯函数**（`service/auth/Datapass.kt` 决定全值还是掩码）+ 一个权限码 63，掩码只作用于出参字符串，筛选/排序/对账一律读原列。代价：每个列表的行装配函数都要自己记得调 `Mask.*`，漏一处就是漏一列；缓解：六处旧掩码本批一并收敛进同一个 `Mask`，`MaskTest` 钉形状，冒烟按"同一行、两个账号、逐字比对"断 | §23.3-12 MASK-R1 的落地形状。验证：冒烟「PT-A1 行的 mobile_masked 与 mobile 并存（收敛到 common 的 Mask 之后形状没变）」等六条；e2e 用例 2/4/8/11 |
| D69 | **`trade_rate` 加店铺归属两列**：源系统评价表只有 `goods_id/item_id`，加盟体系里"这条差评是谁家的"是运营的第一筛，所以本批 ALTER 出 `distributor_id`/`shop_id`，落评价时从订单行带下来（与 C177"归属在落单那刻定死"同一条纪律，不回改历史行）。代价：老库上这两列靠 `ADD COLUMN IF NOT EXISTS` 补，列不存在时那一行根本装配不出来——症状是 **500 而不是出参少一列** | 冒烟「本批 ALTER 的三列在老库上真的补上了（列不存在时这一行根本装配不出来，500 而不是出参少键）」；e2e 用例 8/9 |
| D70 | **一单一申请（`orders_invoice.order_id` 收紧 + 加 uk）**：源系统那一列允许"逗号分隔多订单"，本项目收紧成单个订单号并建 `uk_invoice_order(company_id, order_id)`，`invoice_amount` 只对着这一单的实付算；`pending` 态二次申请因此走 upsert 而不是插行。代价：多单合开做不了，属 P2（放开时去掉 uk、金额改按 `order_id` 列表求和） | §23.3-8 INV-R1 的落地。验证：冒烟「金额只对着这一单的实付算（INV-R1 一单一申请）」「pending 态二次申请 = upsert 同一条」；e2e 用例 13 |
| D71 | **充值代客单不走支付**（`CZ-A3`）：源系统的后台充值同样是"直接改余额 + 记一条流水"，本项目把它落成一条 `deposit_trade(trade_type=recharge, status=SUCCESS)`，单号 `CZ` 前缀、`rel_id` 指向自己那张 trade——**不开第二条入账腿**，所以账实相符那条恒等式对代客充值同样成立，不需要为它单开判据 | 冒烟「CZ-A3 代客充值不走支付、当场入账」「代客充值单号 CZ 前缀（号段发号在常驻库上有行）」「代客充值：Δ余额 == Δ成功动账」；e2e 用例 3 |


## 25. P2 端点规格 —— 开放能力：openapi 网关 + Webhook 出站（M2-1 开发前补齐，追加于 §24 之后，不回改上文）

盘点结论（本轮只读调研得出，读的是 `thirdparty/ECShopX` 原文与本项目源码，不是推断）：**入向有网关、出向没有 Webhook**。

1. 源系统的开放接口是一个 **Lumen/Dingo 形态的单入口网关**，不是 148 条路由：`routes/thirdparty/openapi.php` 只注册了三条 `ANY`（`/api/openapi`、`/api/openapi/{method}`、内部那条 `openapi/exc.operator.resetpwd`），中间件是 `OpenapiCheck` + `HandleResponseMiddleware`；`method` 名 → 控制器动作的映射写在 `Traits/OpenapiRoutes.php` 的一张常量表里（`v1.0` 47 条、`v2.0` 101 条，键形如 `ecx.order.deliver`）。
2. **验签只有一处**（`Middleware/OpenapiCheck.php`）：必传 `version/timestamp/app_key` → `debug` 开关可跳过全部校验 → `abs(time()-strtotime(timestamp)) > 60*10` → 缺 `sign` → 按 `app_key` 查 `openapi_developer` 取 `app_secret` → 比签 → 防重放（`nonce`/`request_id` 二者其一必填，`CacheOpenapiNonceStore` 以 `sha256(nonce)` 为键 `add(key,1,600)`）。闸门顺序就是错误码顺序，照抄。
3. **应答信封不是淘宝式**（里程碑原话"淘宝式应答包裹"与源系统不符，见 §25.6 与后续 D 系列）：`HandleResponseMiddleware` 固定成 `{"status":"success","code":"E0000","message":"成功","data":原值?:null}`，失败 `{"status":"fail","code":"E".sprintf("%04d",code),"message":…,"data":null}`，**HTTP 恒 200**。全仓搜不到 `error_code`/`sub_code`/`result` 三个字段。所谓"开关"在源系统只有一处：`getResponseTypeByVersionAndMethod()` 的豁免表（`1.0` 的 `ecx.wxapp.qrcode` → `image` 不包裹）。
4. 密钥表就一张：`openapi_developer`（`developer_id/company_id/app_key/app_secret/external_base_uri/external_app_key/external_app_secret` + 时间戳，`idx_company_id`）。**没有 status 列、没有 token 表、没有 API 日志表**——意味着源系统"吊销一个 app_key"只能删行，而删行会把历史出站配置一起抹掉。本项目补 `status`（见 25.5）。
5. **出向没有通用 Webhook**：`ThirdPartyBundle\Providers\EventServiceProvider.php` 那张 `事件 → 监听器` 映射是逐家硬编码（MarketingCenter / Shuyun / SystemLink 的 OMS·聚水潭·旺店通），HTTP 客户端 `new Client(['timeout'=>2])`，`MarketingCenter\Request::call()` 里 `catch(\Exception){return [];}` ——**同步、fire-and-forget、零重试、零留痕**。留痕表只有 `systemlink_oms_queuelog`（`company_id/api_type(request|response)/worker/params/result/status(running|success|fail)/runtime/msg_id`）这一张，形状可以借，语义要重做。
6. 出站签名的实样在 `MarketingCenter/Request.php`：把 `data/timestamp/app_key/version/method` 拼成 form、`sign = strtoupper(md5(secret + assemble(参数) + secret))` 再 POST 到对端 `/api/openapi` ——**与入向同一个 `assemble`**，所以本项目这支签名函数必须能同时服务两个方向（25.4）。
7. Kotlin 侧现状：`grep` 全仓 `ApplicationEventPublisher|@EventListener|@TransactionalEventListener|@Async` **零命中**；`service/tx/Db.kt:30` 的 `afterCommit()` 是现成挂点但至今没有调用方；`SecurityConfig` 四条链的位次是 `0 staff(/api/admin/**) → 1 front(/api/h5app/**) → 2 admin(/api/**) → 3 storage`，而 `/api/openapi` 是 `/api/**` 的子集——**这条链必须排在 2 之前**，否则 C174 那一课在同一个文件里重演一遍。

### 25.1 网关面（前缀 `/api/openapi`，鉴权 = `app_key` + `sign`，不进 JWT 链）

| ID | 方法 | 路径 | 说明 |
|---|---|---|---|
| OA-G1 | ANY | `/api/openapi` | 单入口：`method` 取自查询参数（或表单字段）`method`；`version` 缺省 `1.0`；`HandleResponse` 用的也是这两个 query 位（源系统同形，实现期别从 body 里读 version） |
| OA-G2 | ANY | `/api/openapi/{method}` | 同上，`method` 取自路径末段；此时 `version` 只能从 query 拿。两条入口共用同一个分发函数与同一套闸门，**不写第二条链** |

**验签闸门顺序与错误码**（逐条照抄 `OpenapiCheck`，顺序即契约——先缺参再超时再缺签再错签，重排会让对接方的排障顺序错）：

| 位次 | 判据 | 码 | message |
|---|---|---|---|
| 1 | `version`/`timestamp`/`app_key` 任一缺失 | `E4001` | `版本号必填` / `timestamp必填` / `app_key必填` |
| 2 | `ecx.openapi.debug=true` → 跳过 3..8，`company_id` 取系统默认租户 1 | — | — |
| 3 | `abs(now − timestamp) > ecx.openapi.timestamp-window-seconds`（默认 600） | `E4002` | `timestamp 不合法` |
| 4 | 缺 `sign` 或空串 | `E4004` | `缺少 sign` |
| 5 | 按 `app_key` 查不到**或查到但 `status=0`** | `E4003` | `app_key 不正确` |
| 6 | 签不对 | `E4004` | `sign 不合法` |
| 7 | 缺 `nonce` 且缺 `request_id` | `E4001` | `缺少 nonce 或 request_id` |
| 8 | `nonce` 已用过（TTL 600 内重复） | `E4002` | `nonce 已使用` |
| 9 | 入参自带 `company_id` 且不等于密钥所属租户 | `E4001` | `company_id 与密钥所属企业不一致`（fail-closed，源系统 `Filter/BaseFilter::resolveCompanyId` 同判据） |
| 10 | `version` 在注册表里不存在 / `method` 在该版本不存在 / HTTP verb 与注册不符 | `E4006` / `E4005` / `E4005` | `该版本号不存在API` / `找不到API` / `找不到API`（verb 不符在源系统就是 `API_NOT_FOUND`，同码照抄） |
| 11 | 业务异常（委托的 service 抛 `BizException`） | `E5000` | 原 message 透出，`data:null` |

**签名算法**（`service/openapi/SignService.kt`，入向校验与出向包裹共用同一支函数，`assemble` 的规则一条都不许"顺手改"）：

```
sign = uppercase( md5( secret + assemble(params) + secret ) )
assemble(map): 键按字节序升序排序（PHP 的 ksort(…, SORT_STRING)）后，逐项拼 "键 + 值"：
  · 值为 null 的项整体跳过（键也不出现）
  · 布尔 → "1"/"0"
  · 值是对象/数组 → 递归 assemble（同样排序；列表按 "0"、"1"、"2" 的字符串序参与，因此 10 会排在 2 之前）
  · 值是数字 → 用请求里的字面量文本，不做二次解析（JSON 的 `1.0` 与 `1` 是不同的串，PHP 侧按字符串拼接）
  · `sign` 自身不参与
参与签名的参数集 = query 参数 ∪ 请求体字段（源系统 `$request->toArray()` 就是合并后的全集；只签 body 或只签 query 都对不上对方的签）
```

**应答信封**：成功 `{"status":"success","code":"E0000","message":"成功","data":{…}|null}`；失败 `{"status":"fail","code":"E40xx","message":"…","data":null}`；**HTTP 状态恒 200**（含 4001..5000），与 `ecx.compat.force-ok-status` 无关——这一面的状态码语义由信封里的 `code` 承担，不是本项目全局那条兼容开关能改的。`data` 空值一律 `null` 而不是 `[]`（源系统注释就为这件事写过一句"在其他语言里 data 属于对象"）。

### 25.2 首批 method 清单（19 条 / 6 族，命名与 verb 沿用源系统 `OpenapiRoutes`）

注册表是 `service/openapi/OpenapiMethodRegistry.kt` 里的一张表：`version → method → (verb, 委托函数)`。**新增一条 method 只该动这张表 + 一个委托函数**，不许再开第二个入口。首批只收已有实现撑得住的（`exc.operator.resetpwd` 那种内部面、`ecx.image.*`/`ecx.wxapp.*`/`ecx.jurisdiction.*` 依赖未落地的第三方凭据面，都留 M2-3 逐族补）。

| ID | method | 版本 | verb | 委托实现 | 强制筛/口径 |
|---|---|---|---|---|---|
| OA-M1 | `ecx.order.list` | 1.0/2.0 | GET | `OrderQueryService.list(raw, companyId, userId, ALL)` | `user_id` **必填**（按会员取单，缺 → `E4001`）；两个版本都注册、指向同一委托（源系统两版分开实现，本项目不为同名 method 写两套读口） |
| OA-M2 | `ecx.orders.sold.get` | 2.0 | GET | 同上，`userId=null` | 全租户搜索面，接 `status`/`distributor_id`/`time_start_*`；**与 M1 的差别只在这条不锁会员** |
| OA-M3 | `ecx.order.get` | 2.0 | GET | `OrderQueryService.detail(...)` | `order_id` 或 `order_bn` 其一必填；跨租户与不存在**同回 `E5301`**，不回答"这一单在不在" |
| OA-M4 | `ecx.order.deliver` | 2.0 | POST | `OrderFlowService.ship(orderId, form, operatorId=null, operator="openapi:{developer_id}")` | 留痕里写明是开放接口发的货（`operator` 前缀 `openapi:`），与运营/员工两条面区分开；状态机不放开任何态 |
| OA-M5 | `ecx.order.writeoff` | 2.0 | POST | `WriteOffService.writeOff(companyId, orderId, code, scope, operatorId=0, operator="openapi:{developer_id}")` | 取货码必填；`scope` 用密钥所属租户 + `distributor_id` 入参（不传 = 全租户自提点） |
| OA-M6 | `ecx.product.sku_list` | 1.0 | GET | `ItemService.skuList(raw, companyId)` | 与 GOODS-7 同一装配，出参逐字段一致（门槛②比这个） |
| OA-M7 | `ecx.items.entity.get` | 2.0 | GET | `ItemService.list(raw, companyId, front=false)` | 后台口径（含未上架），`status`/`category_id`/`keywords` 透传 |
| OA-M8 | `ecx.item.entity.get` | 2.0 | GET | `ItemService.adminDetail` + 本批给 `ItemsMapper` 新增的 `findByBn` | 按 `item_bn` **精确**取 SPU（现库只有 `LIKE` 那一条，模糊匹配会让"查一个拿到十个"） |
| OA-M9 | `ecx.member.query` | 1.0 | GET | `MembersMapper.findByMobile` | `mobile` 必填；**出参手机号恒掩码**（数据出境面没有"看全值"这个权限位，见 25.6 的 MASK 裁定） |
| OA-M10 | `ecx.member.detail` | 2.0 | GET | `MemberService.profile(companyId, userId)` | 同上掩码 |
| OA-M11 | `ecx.member.list` | 2.0 | GET | 本批给 `MembersMapper` 新增的 `count`/`page` 一对（同一份筛选字典） | `keyword`/`grade_id`/`created` 区间；`PageHelper.paginate` 双 SQL 同筛（C194 那条纪律在这一面同样成立） |
| OA-M12 | `ecx.member_point.detail` | 2.0 | GET | `PointService.overview(userId, companyId)` | 与 PT-F1 同装配 |
| OA-M13 | `ecx.member_point_log.list` | 2.0 | GET | `PointService.adminLogs(companyId, raw, full=false)` | `user_id` 必填、时间段透传；**`full` 恒 `false`**（同 M9 的出境掩码） |
| OA-M14 | `ecx.member_point.update` | 2.0 | POST | `PointService.adjust(companyId, form)` | 与 PT-A2 同一条动账口，`remark` 必填、负数钳到 0；不另开"开放接口专用加分"腿 |
| OA-M15 | `ecx.member.recharge.trade.get` | 2.0 | GET | `DepositService.adminTrades(companyId, raw, full=false)` | `user_id` 选填；`NOTPAY` 单出得来但不进任何对账式（C189） |
| OA-M16 | `ecx.discountcard.list` | 1.0/2.0 | GET | `CouponService.list(companyId, raw)` | 券模板列表（不是会员已领的），与 `ecx.userdiscount.list` 分开——后者要会员维度，留 M2-3 |
| OA-M17 | `ecx.discountcard.send` | 2.0 | POST | `CouponService.send(companyId, CouponSendForm)` | `coupon_id` + `user_ids[]`；幂等判据仍是既有的"同一会员同一券不重发"，网关不另加一层 |
| OA-M18 | `ecx.distributor.list` | 2.0 | GET | `DistributorService.list(companyId, raw, scope=ALL)` | 只出租户内已审核通过且在营业的店（`is_valid=1`），与前台 DS-F1 同判据 |
| OA-M19 | `ecx.distributor.detail` | 2.0 | GET | `DistributorService.detail` | 越租户 → `E5401`（同"找不到"形状） |

### 25.3 后台面（前缀 `/api`，鉴权 = 运营 JWT + `@RequiresResource`，写路径 `@Audited`）

后台这六条走 `/api/setting/openapi/*`（源系统 `routes/api/openapi.php` 里 `GET /api/setting/openapi/developer` 的同一条前缀），**不是** `/api/openapi/*`：网关那条链要 `permitAll`（它的鉴权由 `app_key + sign` 自己承担），而 `/api/openapi/*` 与"`/api/openapi/developers` 是后台的一条例外"**在安全匹配器里没法共存** —— 前者一放行，后台那六条就一起匿名了。前缀错开之后这件事不需要任何豁免表，路径本身就是判据（C174 那条"链的位次就是这条链的难点"的同族坑）。

| ID | 方法 | 路径 | 说明 |
|---|---|---|---|
| OP-A1 | GET | `/api/setting/openapi/developers` | 开发者列表（`keyword?`/`status?`，分页）：行含 `app_key`（**全值出**，它是身份不是秘密）、`app_secret` **永不出**（只在 A2 的响应里出现一次，见 25.4-C1）、`external_base_uri` 脱敏到 host |
| OP-A2 | POST | `/api/setting/openapi/developer` | 新建：`{name(Y), remark?, external_base_uri?, external_app_key?, external_app_secret?}` → 服务端发 `app_key`/`app_secret`（**只有这一条响应的 `data` 里带 secret**，改密钥同理），`status` 默认 1 |
| OP-A3 | PUT | `/api/setting/openapi/developer/{id}` | 改档案与出站三列 + `status` 启停；**不接受 `app_key` 入参**（身份不可改，改身份等于把历史投递记录的归属换人） |
| OP-A4 | POST | `/api/setting/openapi/developer/{id}/reset_secret` | 重置入向密钥：新值当场出一次，旧值**下一个请求即 `E4004`**（没有宽限期——密钥泄露的处置要的是"立刻"，宽限期是运营上的幻觉） |
| OP-A5 | GET | `/api/setting/openapi/methods` | 已注册 method 目录（对接文档面）：`{version, method, verb, 说明}`，只读、来自注册表不来自库 |
| OP-A6 | GET | `/api/setting/openapi/developer/{id}/stats` | 该开发者的调用与投递概况：网关调用计数/失败计数 + 出站 `success/fail/pending` 三态数（读 25.5 新增的两张表，**不在网关侧写调用日志**，理由见 25.4 末条） |
| WH-A1 | GET | `/api/webhook/subscriptions` | 订阅列表（`developer_id?`/`topic?`/`status?`，分页） |
| WH-A2 | POST | `/api/webhook/subscription` | 新建：`{developer_id(Y), topic(Y), url(Y http/https)}`；**该开发者的 `external_app_secret` 未配置 → 422**（没出站密钥就没有能验得通的推送）；`url` 不是 https 且非本机回环 → 422（生产形态里明文推 HMAC 载荷等于把签名白送） |
| WH-A3 | PUT | `/api/webhook/subscription/{id}` | 改 `url`/`topic`/`status`；改址**不追改历史记录**（已经在路上的那一条仍打到旧 url，重推时才读新址） |
| WH-A4 | DELETE | `/api/webhook/subscription/{id}` | 删订阅：直接删（订阅是配置不是账），但 `webhook_delivery_log` 的行**留着**——它们是对接方"我收到了"的证据 |
| WH-A5 | GET | `/api/webhook/deliveries` | 投递记录（`topic?`/`status?`/`msg_id?`/`developer_id?`/时间段，分页）：行含 `attempts`/`last_http_status`/`next_try_at`/`runtime` |
| WH-A6 | GET | `/api/webhook/delivery/{id}` | 详情：请求体（`payload`）、最后一次响应体、签名头（`X-Ecx-Signature` 出**当次实际值**，不是重算值——排"对方说验不过"只有这一个办法） |
| WH-A7 | POST | `/api/webhook/delivery/{id}/retry` | 手动重推：行重置为 `pending`、`next_try_at=now`、`attempts` **不清零**（清零会让"这一条到底试过几次"失去意义），投递时 `msg_id` 不变 |
| WH-A8 | GET | `/api/webhook/topics` | 事件目录 + 每个 topic 的载荷字段说明（读注册表，与 OP-A5 同一个纪律：文档面从代码出，不另维护一份会漂的表） |
| WH-A9 | POST | `/api/webhook/test` | 发一条测试事件到指定 `url`：`{url(Y), developer_id(Y), topic?=webhook.test}`，签名与正式推送同一套；**落一条 `delivery_log`**（测试也要留痕，否则对接方拿"测试通了"当证据时对不上） |

### 25.4 事件与出站（Webhook）

**发布 seam**：`service/openapi/EventPublisher.kt`，写路径**在事务内**落 outbox 行（不是 `Db.afterCommit` 里发 HTTP——提交后进程崩了事件就丢了；outbox 的意义就是"事件与业务变更同事务、同生死"），投递动作**在事务外**：`afterCommit` 里唤醒一次即时投递，`@Scheduled` 兜后续与重试。

| topic | 挂点（现有类/方法） | 载荷 `data` |
|---|---|---|
| `order.created` | `OrderCreateService.create()` 提交后 | `{order_id, order_bn, user_id, distributor_id, shop_id, pay_fee, status, created}` |
| `order.paid` | `OrderFlowService.markPaid()` **CAS 命中那一支** | `{order_id, order_bn, pay_fee, pay_type, pay_time}` |
| `order.shipped` | `OrderFlowService.ship()` | `{order_id, ship_sn, logistics_id, delivered}` |
| `order.completed` | `OrderFlowService.confirmReceipt()` | `{order_id, finish_time}` |
| `order.closed` | `cancelByUser()` / `timeoutCancel()` 状态迁移命中后 | `{order_id, reason, closed_at}` |
| `goods.created` / `goods.updated` / `goods.deleted` | `ItemService.create()/update()/delete()` | `{item_id, item_bn, goods_name, price, store, status}` |
| `refund.finished` | `RefundService.confirmByCallback()` | `{refund_id, order_id, refund_fee, finished_at}` |
| `webhook.test` | 只有 WH-A9 | `{echo: 入参原样, sent_at}` |

**载荷信封**（POST 到订阅 `url`，`Content-Type: application/json`）：`{msg_id, topic, company_id, developer_id, occurred_at, data:{…}}`。

**两层签名**（对接方可以任选一支验，本项目 e2e 两支都验）：
- `X-Ecx-Signature: sha256=<hex( HMAC-SHA256( external_app_secret, timestamp + "\n" + rawBody ) )>` + `X-Ecx-Timestamp` + `X-Ecx-Msg-Id` + `X-Ecx-Event` —— 里程碑要求的 HMAC 头，覆盖**原始字节**，不受 JSON 重排影响。
- 体内 `sign` 字段 = `strtoupper(md5(secret + assemble(信封参数含 data 递归) + secret))` —— 与入向、与 PHP 侧 `MarketingCenter\Request` 同一支函数，让 PHP 形态的接收端不改代码就能验。`sign` 由发送侧计算并**加入信封后再序列化**，接收方按"去掉 `sign` 后重组"比对。

**投递状态机**：`pending → running → success` / `→ pending`（可重试失败，`next_try_at = now + min(2^attempts,32) × backoff_base`）/ `→ fail`（`attempts ≥ max_attempts` 的终态，不再自动排，只能 WH-A7 手动重推）。超时 `ecx.webhook.timeout-ms`（默认 2000，照抄源系统 Guzzle 的 `timeout=>2`）；非 2xx 与连接失败都算可重试失败，HTTP 200 但响应体不是 `success` **不算失败**（对端应答格式不由本项目裁定）。

**幂等与重复**：`msg_id` 在"一个业务事件 × 一个订阅"这一层唯一（`SeqName.WEBHOOK_MSG` 发号），接收方靠它去重；同一事件的多次重试与手动重推**都用同一个 `msg_id`**（换号会让对端把同一件事收到两次且无从判重）。同一 topic 两条订阅各自成行、互不影响。

**调用侧不落日志表**（`OP-A6` 的网关计数走内存计数器 + `operator_logs` 里没有一条 openapi 行）：源系统连一张 API 日志表都没有，本项目**不在这一批造**——理由是网关每次调用都插一行日志会把 P2 的开放面变成写放大源，配额与计量属 M2-4 生产化（那时要的是"每租户每天调用量"的聚合，不是逐次流水）。这条裁定写出来是为了让后面那批知道现在为什么没有。

### 25.5 数据与配置追加（写实现前先定形状，全部幂等）

- **`openapi_developer`**（新表，形状对齐源系统 `Entities/OpenapiDeveloper.php` 再加一列）：`developer_id BIGINT AUTO_INCREMENT PRIMARY KEY, company_id BIGINT NOT NULL, app_key VARCHAR(64) NOT NULL, app_secret VARCHAR(64) NOT NULL, name VARCHAR(64), remark VARCHAR(255), status TINYINT NOT NULL DEFAULT 1, external_base_uri VARCHAR(255), external_app_key VARCHAR(64), external_app_secret VARCHAR(64), created BIGINT NOT NULL DEFAULT 0, updated BIGINT NOT NULL DEFAULT 0` + `uk_openapi_app_key(app_key)` + `idx_openapi_company(company_id)`。`status` 是本项目补的（源系统没有吊销手段）。
- **`webhook_subscription`**：`id PK, company_id, developer_id, topic VARCHAR(64), url VARCHAR(255), status TINYINT DEFAULT 1, created, updated` + `uk_webhook_sub(developer_id, topic, url)` + `idx_webhook_sub_topic(company_id, topic)`。
- **`webhook_delivery_log`**（outbox，借 `systemlink_oms_queuelog` 的形、换它的语义）：`id PK, company_id, developer_id, subscription_id, msg_id VARCHAR(40), topic VARCHAR(64), url VARCHAR(255), payload CLOB, status VARCHAR(16) NOT NULL DEFAULT 'pending', attempts INT NOT NULL DEFAULT 0, next_try_at BIGINT NOT NULL DEFAULT 0, last_http_status INT NOT NULL DEFAULT 0, last_error VARCHAR(255), response_body CLOB, runtime INT NOT NULL DEFAULT 0, created, updated` + `idx_webhook_due(status, next_try_at)` + `idx_webhook_msg(msg_id)` + `idx_webhook_company_created(company_id, created)`。
- 发号：`SeqName` 追加 `WEBHOOK_MSG("webhook_msg", "WH")`，`sql/schema.sql` 同批补 `INSERT IGNORE INTO seq_generator (seq_name, current_value) VALUES ('webhook_msg', 0)`（C166 那条纪律：缺行是 500 不是 422）。
- 权限码从 **65** 起（现最大 = 64）：`65 openapi.lists`、`66 openapi.update`（含 A2/A3/A4）、`67 webhook.lists`、`68 webhook.update`、`69 webhook.push`（重推与测试事件）、目录码 **70 `openapi_center`**。**role 2 一条都不配**：开发者密钥与推送地址是整个租户对外的口子，店铺运营连"看有哪些密钥"都不该有（D57/C167 同一条划分的延伸）。
- `app_key`/`app_secret` 的形状：`ak_` + 16 位十六进制 / `sk_` + 32 位十六进制（`SecureRandom`，不从 `md5(passport_uid)` 派生——源系统那支派生在演示库里等于把密钥公开）。**seed 里的是演示假密钥**，只用于本地自签自验，与 bcrypt 那批演示口令同一惯例。
- 配置（`application.yml` 的 `ecx.*` 块追加，全部走 `@Value`，本仓没有 `@ConfigurationProperties` 的先例、不为这一批开）：`ecx.openapi.enabled`=`true`、`ecx.openapi.debug`=`${ECX_OPENAPI_DEBUG:false}`、`ecx.openapi.timestamp-window-seconds`=`600`、`ecx.openapi.nonce-ttl-seconds`=`600`、`ecx.openapi.wrap-response`=`true`、`ecx.webhook.enabled`=`${ECX_WEBHOOK_ENABLED:true}`、`ecx.webhook.timeout-ms`=`2000`、`ecx.webhook.max-attempts`=`5`、`ecx.webhook.backoff-base-seconds`=`30`、`ecx.webhook.batch-limit`=`20`，作业走既有命名 `ecx.schedule.webhook-deliver-initial-delay-ms`/`ecx.schedule.webhook-deliver-ms`（缺省给大值，测试里像 `order-timeout` 那样推到 1 小时，由 e2e 自己直接调投递函数——**这条不是偷懒**：定时作业在测试里的行为就是"永远不跑"，断它等于不断）。

### 25.6 门槛用例（进 e2e 与冒烟，任一条红就不算交付）

1. **签名链正反十二式**：签对的请求 `E0000` 出数据；**改一个字节**（值、键顺序、布尔、嵌套数组任一处）→ `E4004`；缺 `sign`/缺 `app_key`/缺 `version`/缺 `nonce` → 按 25.1 位次各回各码；`timestamp` 偏移 ±601 秒 → `E4002`、±599 秒 → 通过（**边界两个方向都要测**，只测超窗等于没测窗口）；同一 `nonce` 连打两次 → 第二次 `E4002` 且**业务侧没有副作用**（重放在这里必须挡在委托之前，不是靠委托幂等兜）；`status=0` 的密钥 → `E4003`；`debug=true` 时不验签（并断默认必须是 `false`）。签名的**跨语言一致性**另测一次：同参数同密钥用 PHP 的 `assemble` 语义（含列表序 `"0","1","10","2"`、布尔 `1/0`、null 整体跳过）逐字对。
2. **分发与租户边界**：未知 `version` → `E4006`；未知 `method` → `E4005`；verb 与注册不符 → `E4005`（**不是 405**，源系统就这码，改了就漂）；`ecx.order.get` 的出参与 `ORDER-9` 同一条单的出参**逐字段相同**（同一委托同一装配函数，这是"网关只是另一张脸"的唯一证明方式）；seed 的第二个租户密钥（`company_id=2`，库里没有它的业务数据）读 `ecx.items.entity.get` 得 **0 行**而不是报错，且它在入参里写 `company_id=1` → `E4001`（fail-closed）；`M9/M13/M15` 三面的手机号在网关面**恒掩码**，与 `datapass.pass` 无关。
3. **出站链**：e2e 起一个本机假接收服务器（复用 `SimAliPayServer`/`SimWxPayServer` 的夹具惯例，命名 `SimWebhookReceiver`）——支付结清后收到 `order.paid`，**HMAC 头与体内 `sign` 两支都验通**、载荷字段逐个对；服务器先 500 一次再 200 → 记录走到 `success` 且 `attempts=2`、`next_try_at` 按 `2^n×30` 排过；服务器始终 500 → 走到终态 `fail` 且不再自动排，`WH-A7` 手动重推后**同 `msg_id`** 再打一次；订阅 `status=0` 时事件不投（`pending` 都不生成）；`markPaid` 的 CAS 未命中（重复回调）时**不生成第二条事件**；`WH-A9` 测试事件在 `delivery_log` 里查得到。
4. **不碰账、不碰状态机**：挂了事件发布之后，既有回归必须原样绿——`m15_reconcile_smoke.py` 的账实相符恒等式、`BUCKET-R1` 六桶求和、`m06` 的并发不超卖一条都不能变红；`mvn -B -o verify` 全绿 + 契约测试出参形状钉住 + `tools/m21_openapi_smoke.py` 在 18080 实跑（含用 Python 自己复算一遍签名——**脚本必须能独立算出签**，否则它只是在重复实现里自证）。


## 26. 实现期语义补充（M2-1 #76..#81：openapi 网关 + Webhook 出站落地时确认，追加于 §25 之后，不回改上文）

交付面：网关 **OA-G1/G2 两条入口 + 十一条闸门 + 19 条 method**（`OpenapiMethodRegistry` 一张表，V1 4 条、V2 19 条、两版都注册 2 条）、后台 **OP-A1..A6 + WH-A1..A9 共 15 条**、三张新表（`openapi_developer`/`webhook_subscription`/`webhook_delivery_log`，基表数 71→74）、权限码 **65..70**（role 2 一条都不配）、事件挂点 **9 处**（`OrderCreateService` 1 + `OrderFlowService` 4 + `ItemService` 3 + `RefundService` 1）、`SeqName.WEBHOOK_MSG` 号段。新代码落位：`service/openapi/{OpenapiSign,OpenapiGatewayService,OpenapiMethodRegistry,EventPublisher}.kt`、`service/webhook/{WebhookService,WebhookDeliveryService,WebhookDeliveryJob,WebhookTopics}.kt`、`dao/openapi/`、`dao/webhook/`、`web-admin/{OpenapiAdminController,WebhookAdminController}.kt`、`web-front` 侧一条不动。验证面：`M21EndToEndTest` **20 例**（surefire `Tests run: 20, Failures: 0`）+ `contract/{OpenapiContract,WebhookContract}.kt` **21 个出参契约集合**（含一张 `FORBIDDEN` 黑名单：`app_secret`/`external_app_secret`/`secret` 三个键在任何列表出参里出现即失败）+ 夹具 `SimWebhookReceiver.kt` + `tools/m21_openapi_smoke.py` **92 项实跑全绿**（18080 常驻库），`mvn -B -o verify` BUILD SUCCESS——**ecshopx-boot 283 例 0 失败 0 错误**（另有 common 21 / dao 12 / service 30，common 那条 `All coverage checks have been met.` 同过）；门槛④那三张既有回归同库复跑：`m06` 48/48、`m11` 83/83、`m15_reconcile` 169/169。**§25.4 那张载荷表与本节有 5 处字面差异，以 `WebhookTopics` 与本节为准（C201），上文不回改。**

### 26.1 口径确认（C 系列续编）

| # | 口径 | 实测依据 |
|---|---|---|
| C198 | **防重放判的是"这一位归谁"，不是"返回值等不等于我算的 expiry"**：`OpenapiNonceStore.consume` 用 `compute` 原子地把 `sha256(nonce)` 这个槽位交出去——只有"原本空着或已过期"的那一次拿到所有权并返回 `true`。旧写法判 `stored == expiry`，而**同一秒内**重放同一个 nonce 时两次算出的 `expiry` 完全相同，第二次拿到旧值就等于拿到新值 → 放行。这一条是门槛①的重放用例抓出来的**真缺陷**，不是测试写歪：脚本连发恰好落在同一秒，那一档正是攻击者最省事的一档 | e2e @Order(5)「重放挡在委托之前 业务侧一位没动」；冒烟「同一 nonce 二次打写面：E4002 且余额没再动（挡在委托之前，不是业务幂等兜住）」「换 nonce 后同一条写面仍可执行（并回冲，账回到基线）」 |
| C199 | **归属闸门必须在委托之前，且"不存在"与"不归你"同答**：`OrderQueryService.detail` 与 `OrderFlowService.ship` 都是**按单号操作**的站内装配函数——后台面靠 `DistributorScope` 收窄、会员面靠 `userId` 校验，开放面两个都没有（租户位来自密钥行，不来自入参）。所以 `requireOwnedOrder(caller, id)` 先 `findById` 比 `companyId`，不同就 `E5301 订单不存在`；说出口"这单存在但不是你的"，网关就成了别的租户的单号探测器。会员、分销商两面同判据（`E5501`/`E5401`） | 冒烟「签对但单号不是你的 -> E5301（不存在与跨租户同答：归属闸门在委托之前）」「别租户拿同号会员 -> E5501」「company 2 的密钥读不到 company 1 的商品（0 行而不是报错）」；e2e @Order(7)/(9) |
| C200 | **`OP-A6` 的计数分两桶，闸门阶段的失败不落开发者头上**：`metrics.bind(caller)` 只在第 10 道闸门与 `checkParams` 都过了之后才绑——错签、错 `app_key`、超窗那几道连"你是谁"都还没认出来，计入某个密钥的 `fail` 等于替攻击者记账。认出调用方之后失败在委托里的（`E5301`/`E5401`/`E5501`/`E5000`）才进这一位。这条口径同时决定了**实跑断言只能写成增量式**：常驻库上 `total` 是历史累计，绝对值证不了任何事 | 冒烟「闸门阶段就失败的不落在这个开发者头上（它还没被认出来，落 UNKNOWN 桶）」「委托里的失败计入该开发者：total 与 fail 各 +1、success 不动」「OP-A6 概况：上面那一轮的增量到这里原样数得到」；e2e @Order(20) |
| C201 | **载荷字段名唯一来源是 `WebhookTopics`，与 §25.4 那张表有 5 处字面差异**（本仓表形状优先，§25.4 不回改）：① `order.created` **没有 `order_bn`**——本仓 `orders.order_id` 就是发号器出来的单号本身，没有 PHP 那根 `order_bn` 列，造一个不存在的列不如少一个字段（`OA-M3` 的 `order_bn` 入参按同值别名收，见 §25.2 那条注释）；② `order.paid` 同理去掉 `order_bn`；③ `order.shipped` = `{order_id, orders_delivery_id, delivery_corp, delivery_code, delivered}`，不是 §25.4 写的 `{order_id, ship_sn, logistics_id, delivered}`；④ `goods.created`/`goods.updated` 用 `item_name`/`approve_status`，不是 `goods_name`/`status`；⑤ `goods.deleted` 只有 `{item_id, item_bn}` 两键（删行之后其余字段都在回收站里，对端拿不到也不该拿）。`order.completed`/`order.closed`/`refund.finished` 三行与表一致。三处同源：发布侧常量、`WH-A8` 的对外文档面、e2e/冒烟的字段断言都读这一张表 | 冒烟「WH-A8 目录十条、每条带挂点与载荷字段说明」「order.created 载荷字段逐个对」「order.shipped 随发货到达（含 orders_delivery_id 与 delivered 位）」「order.closed 随用户取消到达（reason/closed_at 齐）」；e2e @Order(14)/(19) |
| C202 | **outbox 是"事件 × 订阅"的笛卡尔积，没订阅时一行都不写**：一个 topic 挂两条订阅 = 两行、各发各的 `msg_id`（两家接收端的判重互不相干）；没有启用中的订阅则 `publish` 直接返回空列表——**连 `pending` 都不生成**，不是生成之后再由投递侧跳过（后者会让 outbox 堆满没人要的行，"待投数"这个指标永久失真）。`url` 在发布那一刻抄进行里，`WH-A3` 改址不追改在途那一行 | 冒烟「下单后假接收服务器收到 order.created（事务内落 outbox、提交后即刻投）」「订阅 status=0 之后新事件连 pending 都不生成（不是生成后再跳过）」；e2e @Order(18)。**这条判据一开始是假绿**：脚本只建了 `order.created`/`order.paid` 两条订阅，shipped/completed/closed 三条断的是"必然为空的集合"——补三条订阅之前那三条永远 PASS |
| C203 | **时间戳"定一次、发一次、留一次"**：`ts` 同时是 HMAC 的输入、`X-Ecx-Timestamp` 的值、`last_ts` 的留痕；发完再取当前秒会让留下的那个数对不上真正发出去的那个。`webhook_delivery_log` 因此在 §25.5 那张表之外多两列 `last_signature VARCHAR(128)` / `last_ts BIGINT`——`WH-A6` 要出的是**当次实际发出**的头，不是拿当前密钥重算的值：重算也能算出一个"看起来对"的头，只有对端收到的那一个不能骗人 | 冒烟「WH-A6 详情出的是当次实际发出的签名头」「WH-A6 留痕的头与接收端当次收到的头逐字相同（signature + signature_ts 两位都对）」 |
| C204 | **手动重推只接 `fail`/`running`**：判据写在 `resetForRetry` 那条 SQL 的 `status IN (...)` 里，命中 0 行才抛 422——`pending` 再点一次就是排两次队，`success` 再点一次对端就收到两份。重推**不清 `attempts`、不换 `msg_id`**。终态 `fail` 那一条在本仓由 e2e 证（把 `ecx.webhook.max-attempts` 压到 2），实跑不证：真服务默认 5 次要等 60+120+240+480 秒，脚本不该为一行记录挂十五分钟 | e2e @Order(17)「一直500 走到终态 fail 手动重推同 msg_id」；冒烟三条「pending 的记录手动重推 422」「已成功的记录不能再重推」「对端断开的那条同样不能手动重推」 |
| C205 | **两条投递触发器各自可测，缺一不可**：`afterCommit` 唤醒（即时投，对接方不必等 30 秒）与 `@Scheduled` 作业（退避到期之后**没人点按钮也得自己动**）。e2e 把作业的 `initial-delay`/`period` 推到 1 小时，专断前者；实跑断后者——这一位不是偷懒，定时作业在测试里的行为就是"永远不跑"，断它等于不断 | 冒烟「退避到期后由真实作业投成功：attempts 走到 2、msg_id 不变」「同一个 msg_id 在接收端有两份原始包（500 那一次与作业那一次）」「退避排到 2^1 x 30 秒附近」；e2e @Order(16) 用 `backoff-base-seconds=1` 钉公式形状 |
| C206 | **常驻库上的幂等要主动做**（"脏库把绝对算术跑红"纪律第四次兑现）：`webhook_subscription` 是配置、不会跟着脚本结束而消失，上一轮中断的运行留下 6 条指向同一个 `host:port` 的订阅，本轮的假接收服务器于是同时收到"上一轮订阅"的推送——`len(...) == 1` 那种精确断言当场变成 2。现固定两道：开场 sweep 掉本机端口的残留订阅（**投递记录留着**，它们是证据），接收端把 `path` 记下来、按本轮 `RUN` 过滤 | 冒烟第一行「开场清掉上一轮残留的本机订阅 6 条」；上一轮运行的 `FAIL order.paid 到达且字段逐个对 -> 两条` 就是这一条照出来的 |
| C207 | **400 / 422 / 404 三支分工在开放面同样成立**（C191 的延伸）：读口的筛值词表外（`?status=nope`、`?created_start=abc`）→ **400** 且把可选值/两种可接受写法写进 message；写口的业务规则（未知 topic、没配出站密钥、明文 url、重复订阅）→ **422**；跨租户与不存在 → 同形（网关侧 `E5301`/`E5501`，后台侧 404） | 冒烟「投递状态筛不认识的取值直接拒、且把可选值写在 message 里」「时间段筛给的是脏串 -> 400 并说明两种可接受写法」「未知 topic -> 422」「开发者没有 external_app_secret -> 422」「开发者不存在…-> 404」 |
| C208 | **版本表逐条登记，不做版本降级**：V1 只有 4 条（`ecx.order.list`/`ecx.product.sku_list`/`ecx.member.query`/`ecx.discountcard.list`，其中两条两版都注册、指向同一委托），其余 15 条只在 V2。用 `version=2.0` 打 `ecx.member.query` 是 `E4005 找不到API`，不是"回落 1.0 试一次"——源系统的注册表就是这个形状，降级会让对接方把版本写错这件事永远藏住 | 冒烟「未知 method -> E4005」「未知 version -> E4006」；本批实跑第一版脚本用 V2 打 `member.query` 拿到 `E4005`，照出的正是这一位 |
| C209 | **`timestamp` 与后台读口的时间段共用一套宽容解析**：unix 秒 / 19 位 `yyyy-MM-dd HH:mm:ss` / 10 位日期三种写法都收，词表外才 `E4002`（运营不必两头记）。**测试夹具的坑与产品无关但值得钉住**：MockMvc **不做 percent-decode**——手拼 query string 里那串 `2026-10-19%2023%3A12%3A41` 到 `@RequestParam` 仍是未解码的字面量，日期分支当场红；改用 `.param(k, v)` 之后同一条断言过（实测探针所得，非推断） | e2e @Order(4)「闸门位次 时间窗两个方向 与 debug 旁路」里的日期时间那条；`OpenapiSign` 侧的三种写法解析在 `M21EndToEndTest` 与冒烟 `timestamp +599 仍在窗内` 上各过一遍 |
| C210 | **网关这一面 HTTP 恒 200，码在信封里**：`/api/openapi` 那条链 `permitAll`（鉴权由 `app_key + sign` 自己承担），所以没带签的请求不是 401 而是进闸门回 `E4001`；verb 与注册不符不是 405 而是 `E4005`。这一面的状态码语义由信封里的 `code` 承担，与全局那条 `ecx.compat.force-ok-status` 开关无关 | 冒烟「网关那条匿名链不吃令牌：没签也一样进闸门（E4001 而不是 401）」「verb 不符 -> E4005 而不是 405（HTTP 仍 200）」；`OpenapiContract` 把四键信封钉住 |
| C211 | **基表数 71→74、号段同批补行**：`BootSmokeTest` 那条钉基表数的用例数字随三张新表更新，注释写明是哪三张；`sql/schema.sql` 同批 `INSERT IGNORE INTO seq_generator (seq_name, current_value) VALUES ('webhook_msg', 0)`——缺行时 `SeqGenerator` 抛的是 500 不是 422（C166 那条纪律在每一张新号段表上都成立） | `BootSmokeTest`「sql init 建表 74 张」绿（`mvn -B -o verify` 283 例里的一条） |

### 26.2 与规格/源系统的差异（D 系列续编）

| # | 变更 | 理由/缓解 |
|---|---|---|
| D72 | **出站从"同步 fire-and-forget"改成"事务内 outbox + 作业重试 + 留痕"**：源系统 `MarketingCenter\Request::call()` 是 `new Client(['timeout'=>2])` + `catch(\Exception){return [];}` ——零重试、零留痕，进程在"提交之后、发出之前"被杀事件就永远丢了。本项目把落行放进业务事务（回滚就一起没有，提交就一定有人投），投递动作放事务外，`afterCommit` 唤醒只是加速、真正的兜底是 30 秒作业。代价：多两张表、一支作业、一个会堆积的 outbox（`idx_webhook_due` 就是为它建的），以及"至多一次"要靠 `claim` 的 CAS 位与 `msg_id` 判重共同保证 | §25.4 的落地。验证：e2e @Order(14)/(16)/(17)/(18)；冒烟第 6/7/8 段共 26 条 |
| D73 | **出站两层签名**：`X-Ecx-Signature: sha256=<HMAC(secret, ts + "\n" + rawBody)>` 覆盖**原始字节**（JSON 重排不影响它），体内 `sign = strtoupper(md5(secret + assemble(信封去 sign) + secret))` 与入向、与 PHP 侧 `MarketingCenter\Request` 同一支 `assemble`。源系统只有体内那一层。代价：对接方要写两套验签；缓解：**任选一支**，本项目 e2e 与实跑两支都验，且 `SimWebhookReceiver`/`tools/m21_openapi_smoke.py` 各算一遍（Kotlin、Kotlin 独立实现、Python 三种实现同源对签） | 冒烟「第一层：X-Ecx-Signature 覆盖原始字节」「第二层：体内 sign 按 assemble 语义验通」「11 元素列表按下标字符串序折叠也能对上签」；e2e @Order(2) 用手写期望串钉 `assemble` |
| D74 | **`openapi_developer.status` 是本项目补的列**：源系统密钥表没有 status、也没有 token 表与 API 日志表，"吊销一个 app_key"只能删行，而删行会把该开发者的历史出站配置与订阅归属一起抹掉。本项目启停即刻生效（停用下一个请求即 `E4003`）、删订阅保留投递记录（订阅是配置，投递记录是证据） | 冒烟「OP-A3 停用即刻 E4003（吊销不是删行，历史留着）」「WH-A4 删订阅但保留投递记录」「删完订阅，证据还在」 |
| D75 | **密钥由 `SecureRandom` 发**（`ak_`+16 位十六进制 / `sk_`+32 位），源系统从 `md5(passport_uid)` 派生——在演示库里等于把密钥公开。seed 里那三对（含 `company_id=2` 那对）是**演示假密钥**，只用于本地自签自验，与 bcrypt 那批演示口令同一惯例；`app_secret` 只在 `OP-A2`/`OP-A4` 的响应里出现一次，列表面连列清单都不含它 | 冒烟「OP-A2 服务端发密钥…且只有这一次带 secret」「OP-A1 出参没有 app_secret」「OP-A4 重置后旧 secret 下一个请求即 E4004（没有宽限期）」 |
| D76 | **不建 API 调用日志表**：`OP-A6` 走进程内计数器（重启归零、多实例各算各的），`operator_logs` 里一条 openapi 行都没有。源系统连一张 API 日志表都没有，本项目也不在这一批造——网关每次调用插一行会把 P2 的开放面变成写放大源；配额与计量属 M2-4（那时要的是"每租户每天调用量"的聚合，不是逐次流水）。代价：这一位的数字**不是账**，写进接口说明里（`stats` 出参带 `note`） | 冒烟「OP-A6 概况…（进程内计数、重启归零）」；`OpenapiContract` 的 stats 行含 `note` 键 |
| D77 | **后台六条走 `/api/setting/openapi/*`，与网关前缀错开**：`SecurityConfig` 四条链的位次是 `0 staff → 1 front → 2 admin → 3 storage`，而 `/api/openapi` 是 `/api/**` 的子集、网关链必须排在 2 之前。同前缀下"网关 `permitAll` + 后台那六条要鉴权"在安全匹配器里没法共存——放行一条就匿名一片（C174 那一课）。前缀错开之后不需要任何豁免表，**路径本身就是判据** | 冒烟第 8 段三条 403（role 2 无 `openapi.lists`/`webhook.lists`/`webhook.push`）与「网关那条匿名链不吃令牌」；e2e @Order(11) |
| D78 | **事件目录做成接口而不是硬编码映射**：源系统对应物是 `EventServiceProvider` 里那张 `事件 → 监听器` 常量表，只有代码能读、对接口没有任何面。本项目把同一张表做成 `WH-A8`（topic + 挂点 + 载荷字段 + 一句说明），与 `OP-A5` 同一个纪律：**文档面从代码出**，另维护一份清单必然漂，漂了受害的是对接方 | 冒烟「WH-A8 目录十条、每条带挂点与载荷字段说明」「OP-A5 目录 = 注册表的 19 个 method、2 个版本」；e2e @Order(10)/(20) |

## 27. P2 端点规格 —— 对接族：SystemLink ERP 入站 + 微信开放平台 + 支付渠道 profile（M2-2 开发前补齐，追加于 §26 之后，不回改上文）

盘点结论（只读调研 `thirdparty/ECShopX` 原文与本仓源码所得，不是推断）：

1. **SystemLink 在源系统是四条互不相干的入站链，不是一个网关**：`routes/systemlink/openapi.php`（`SystemLinkOpenapiCheck`）、`ome.php`（`ShopexErpCheck`）、`jushuitan.php`（`JushuitanCheck`）、`adapay.php`（无中间件）。四条前缀都是 `/api/systemlink`，但**鉴权、签名、应答形状各不相同**——把它们当成"同一个对接口"是这一族最容易犯的错。
2. **签名是三支而不是一支，差异在 `assemble` 而不在公式**：`OpenApiCheck::gen_sign` 与 `ShopexErpCheck::gen_sign` 公式相同（`strtoupper(md5(strtoupper(md5(assemble)).token))`），但前者 `assemble` **不跳过 `null`、不转布尔**，后者**跳过 `null`、布尔转 `1/0`**。同一份参数在两条链上算出的签不同，这是实测结论（见 27.1 那张对照表），不是笔误。token 都取自 `config('common.openapi_gy_token')` / `config('common.oms_token')` 全局配置，**不分租户**。
3. **OME 那张 17 条 method 常量表里一半是反向写商品/会员/分类**（`ome.items.create`/`ome.user.up`/`ome.category.create`/`ome.item.up`），另一半是 ERP 回传交易状态。本批只收 4 条能落到现有服务上的（27.2），其余留 M2-3 逐族补。
4. **`store.trade.status.update` 在源系统只读不写**：拿到 `TRADE_CLOSED` 只查 `cancel_status == SUCCESS` 就答 `true`，**一行数据都不改**（注释写"目前由于关闭订单"，代码里没有任何关闭动作）。照抄这一条是为了让对接方"发完就收到成功"的行为不变，不是为了让它真关单——真关单走 `store.trade.refund.add`。
5. **留痕表 `systemlink_oms_queuelog` 是先落 `running` 行、把 `logId` 传给控制器，控制器结束时补 `result`/`status`**；`runtime` 在源系统是"运行时间(秒)"字符串列。本项目保留同一张表的语义，`runtime` 改用毫秒（与 M2-1 的 `webhook_delivery_log.runtime` 对齐，D 系列记录）。
6. **微信侧有两条互不相同的凭据链**：第三方平台（component）那条管预授权 URL、授权/取消授权/更新授权事件、authorizer info（`OpenPlatform.php`）；公众号/小程序那条管行业设置、模板增删查与发送（`TemplateMessageService`，按 `authorizerAppId` 构造）、小程序码三种形态（`WeappService::getWxaCode` 有限码 / `createWxaCodeUnlimit` 无限码 / `getWxaUrlLink`·`getWxaUrlSchema` 链接与 scheme）。两条链的 access_token 来源不同，混用会在"授权码换了、消息还发得出去"这种场景下才暴露。
7. **支付三家的源系统实现是"开户 + 交易 + 分账 + 提现"四条腿**（AdaPay 一家 20 个 Service 文件），而本项目 `PayChannel` 接口只要四件事：下单出参数包、原路退、查单、回调解析。本批只做这四件事——里程碑原话就是"只做 `PayChannel` 实现位，不逐家联调"。

### 27.1 SystemLink 入站（前缀 `/api/systemlink`，鉴权 = 签名，不进 JWT 链）

| ID | 方法 | 路径 | 中间件等价物 | 说明 |
|---|---|---|---|---|
| SL-G1 | POST | `/api/systemlink/openapi/{method}` | 签名 A（`openapi_token`） | 站点开通/关闭/续费/查询，4 条 method（27.2 表 SL-M1..M4 的 site 组） |
| SL-G2 | POST | `/api/systemlink/ome` | 签名 B（`oms_token`）+ IP 白名单 | `method` 从表单字段取；与 SL-G3 共用同一个分发函数 |
| SL-G3 | POST | `/api/systemlink/ome/{method}` | 同 SL-G2 | `method` 取自路径末段 |

**签名公式（两条链同一支公式）**：`sign = strtoupper(md5(strtoupper(md5(assemble(params))) + token))`，`params` 是"query ∪ 表单/JSON body"去掉 `sign` 之后的**扁平全集**（源系统 `$request->toArray()`）。

**两支 `assemble` 的差异（逐项对照，写实现前先看清）**：

| 输入 | 签名 A（`OpenApiCheck`） | 签名 B（`ShopexErpCheck`） |
|---|---|---|
| 键序 | `ksort(..., SORT_STRING)`，键按字节序 | 同 |
| 拼接 | `键 + 值` 直接串 | 同 |
| `null` 值 | **保留**，拼成 `键` + 空串 | **整体跳过**（键也不出现） |
| 布尔值 | **保留**，PHP 里 `true` 串成 `"1"`、`false` 串成 `""` | **显式转** `1` / `0` |
| 嵌套数组 | 递归 `assemble`（同样排序） | 同 |
| 列表下标 | 按字符串序参与（`"10"` 排在 `"2"` 之前） | 同 |

**错误应答形状（与 §25 的四键信封不是一套，别混）**：`{"rsp":"fail","code":0,"err_msg":"sign error","data":"<被签参数的 JSON 串>"}`，HTTP 恒 200。业务失败同样是 `rsp=fail`；成功是 `{"rsp":"succ","code":0,"err_msg":"","data":{...}}`（源系统 `api_response('true', …)` 落 `rsp=succ`）。

**IP 白名单（只有 OME 那条有）**：源系统 `whiteIP()` 读 `config('common.ome_ip')` 之类的白名单；本项目改成按租户配置（27.3 的 `ip_whitelist`，逗号分隔、支持 CIDR、空 = 不限制），判据在签名之后——**先证明你是你，再判你从哪来**（顺序反了会把"白名单里的陌生人"和"签名错的自己人"混成一个码）。

### 27.2 入站方法清单（8 条：site 4 + OME 4）

| ID | method | 入口 | 入参 | 委托/口径 |
|---|---|---|---|---|
| SL-M1 | `ecapi.site.create` | SL-G1 | `eid`(Y) `shopexid`(Y) `tel`(Y) `company_name`(Y) `days`? | 建 `company` + 超管 operator + `company_license` 一行（`days` 缺省 365×20）；`tel` 已存在 → `账号已开通` |
| SL-M2 | `ecapi.site.close` | SL-G1 | `eid`(Y) 或 `shopexid`(Y) | `company.is_disabled=1`（不删行：删了历史订单的归属就断了） |
| SL-M3 | `ecapi.site.renew` | SL-G1 | `eid`/`shopexid`(Y) `days`(Y) | 续费：`expired_at += days×86400`，从"原到期时间"起算而不是从今天起算（少给一天也是少给） |
| SL-M4 | `ecapi.site.checkisopen` | SL-G1 | `eid`/`shopexid`(Y) | 出 `{is_open, company_id, expired_at, available_days}`；`is_open` = 未停用 **且** 未过期 |
| SL-M5 | `store.trade.fullinfo.get` | SL-G2/3 | `tid`(Y) | 按 `order_id` 出 OME 形状的 `{trade:{...}}`；`order_class` 不是 normal/groups/seckill → 拒（源系统同判据） |
| SL-M6 | `store.logistics.offline.send` | SL-G2/3 | `tid`(Y) `company_code`(Y) `logistics_no`(Y) `oid_list`? | 委托 `OrderFlowService.ship`；`delivery_status=DONE` → `订单已发货，请勿重复发货`；`oid_list` 给了就按拆单发（`delivery_type=sep`） |
| SL-M7 | `store.trade.status.update` | SL-G2/3 | `tid`(Y) `status` `type` `modify` `reason` | **只读不写**（见盘点结论 4）：`status=TRADE_CLOSED && type=status` 且本地 `cancel_status=SUCCESS` → `true`，否则 `订单信息有误` |
| SL-M8 | `store.items.quantity.list.update` | SL-G2/3 | `list_quantity`(Y，JSON 串 `[{bn,quantity}]`) | 按 `item_bn` 回写 `items.store`；不存在的 bn 进 `no_update` 列表**不整单失败**（ERP 一次推一千个 SKU，一个错的不能拖垮九百九十九个） |

### 27.3 后台面（前缀 `/api`，运营 JWT + `@RequiresResource`，写路径 `@Audited`）

| ID | 方法 | 路径 | 说明 |
|---|---|---|---|
| SL-A1 | GET | `/api/setting/systemlink` | 对接设置：`{is_open, openapi_token(掩码), oms_token(掩码), ip_whitelist, updated}` |
| SL-A2 | PUT | `/api/setting/systemlink` | 保存设置；`token` 传空 = 不改（运营看不到全值，也就没法把它抄回去再存一遍）；改 token 即刻生效 |
| SL-A3 | GET | `/api/systemlink/logs` | 调用日志列表（`api_type?`/`worker?`/`status?`/时间段，分页）：行含 `runtime`/`msg_id`，**不出 `params`/`result` 两个 CLOB** |
| SL-A4 | GET | `/api/systemlink/log/{id}` | 详情：带 `params`/`result` 原文（排障只有这一条路） |
| WX-A1 | GET | `/api/wechat/openplatform/pre_auth_url` | 第三方平台预授权 URL（`redirect_uri` 入参） |
| WX-A2 | POST | `/api/wechat/openplatform/bind` | 授权码回调绑定（`authorization_code`） |
| WX-A3 | POST | `/api/wechat/openplatform/direct_bind` | 直连小程序绑定（`app_id` + `app_secret`） |
| WX-A4 | GET | `/api/wechat/openplatform/authorizer` | 当前租户的授权信息（`app_id`/`nick_name`/`verify_type`/`expires_at`） |
| WX-A5 | GET | `/api/wechat/templates` | 模板列表（`type=oa` 公众号模板消息 / `type=wxa` 订阅消息模板） |
| WX-A6 | POST | `/api/wechat/template` | 新增模板（`short_id` 或 `template_id`） |
| WX-A7 | DELETE | `/api/wechat/template/{id}` | 删模板 |
| WX-A8 | POST | `/api/wechat/template/send` | 发一条模板消息/订阅消息：`{touser(Y), template_id(Y), page?, data(Y)}` |
| WX-A9 | POST | `/api/wechat/wxa_code` | 小程序码：`{path, width?, scene?, type=code|unlimit|link|schema}` 四形态一张口 |
| WX-G1 | ANY | `/api/wechat/notify/{appid}` | 公众号/小程序事件回调（消息、关注/取关、模板消息送达） |
| WX-G2 | ANY | `/api/wechat/openplatform/notify/{appid}` | 第三方平台事件回调（`component_verify_ticket` / `authorized` / `unauthorized` / `updateauthorized`），AES 解密后分发 |

### 27.4 支付渠道 profile（`PayChannel` 实现位，不逐家联调）

| ID | 渠道 | `pay_type` | profile 值 | 落地范围 |
|---|---|---|---|---|
| PAY-X1 | AdaPay（汇付天下） | `adapay` | `adapay` | `pay`（出 `payment.create` 参数包）/ `refund` / `queryTrade` / `closeTrade` / `parseNotification`（`payment.succeeded` 回调） |
| PAY-X2 | 银商（BsPay） | `bspay` | `bspay` | 同上四件事（`/v2/trade/payment` + `/v2/trade/refund` + 回调验签） |
| PAY-X3 | 汇付（HfPay） | `hfpay` | `hfpay` | 同上四件事（`merchantPayment` + 退款 + 回调） |

三家的共同约定（写进实现，避免每家各写一套）：`pay()` 只出参数包不结清（`settled=false`），结清只认回调；`refund()` 返回 `pendingCallback`（三家退款都是异步受理）；`parseNotification()` 必须验签，验不过返回 `null`（控制器回 FAIL，**不动账**）；配置读 `pay_channel_config` 表（`company_id + channel` 一行，密钥列加密留给 M2-4）。

### 27.5 数据与配置追加（写实现前先定形状，全部幂等）

- **`systemlink_setting`**（新表）：`id PK, company_id, is_open TINYINT DEFAULT 0, openapi_token VARCHAR(64), oms_token VARCHAR(64), ip_whitelist VARCHAR(512), created, updated` + `uk_systemlink_company(company_id)`。token 是本项目补的（源系统在 `config/common.php` 里全局一份，多租户共用一个 token 等于谁都能替谁开站点）。
- **`systemlink_oms_queuelog`**（借源系统同形）：`id PK, company_id, api_type VARCHAR(16)('request'|'response'), worker VARCHAR(64), params CLOB, result CLOB, status VARCHAR(16)('running'|'success'|'fail'), runtime INT(ms), msg_id VARCHAR(40), created, updated` + `idx_oms_log_company_created(company_id, created)` + `idx_oms_log_worker(worker, status)`。
- **`company_license`**（新表）：`id PK, company_id, eid VARCHAR(64), passport_uid VARCHAR(64), issue_id VARCHAR(64), available_days INT, active_at BIGINT, expired_at BIGINT, goods_code VARCHAR(64), product_code VARCHAR(64), source VARCHAR(32), created, updated` + `uk_license_company(company_id)`。源系统这张表在 `authorization` 包里（不在 `src/` 下），本项目按最小可用列自建。
- **`wx_app`**（新表）：`id PK, company_id, app_id VARCHAR(64), app_secret VARCHAR(128), app_type VARCHAR(8)('oa'|'wxa'), authorizer_refresh_token VARCHAR(255), nick_name VARCHAR(64), verify_type INT, principal_name VARCHAR(128), status TINYINT DEFAULT 1, expires_at BIGINT, created, updated` + `uk_wx_app(company_id, app_id)`。
- **`wx_message_log`**（新表）：`id PK, company_id, app_id, touser VARCHAR(128), template_id VARCHAR(128), page VARCHAR(255), data CLOB, status VARCHAR(16)('pending'|'success'|'fail'), errcode INT, errmsg VARCHAR(255), mock TINYINT, created, updated` + `idx_wx_msg_company_created(company_id, created)`。
- **`pay_channel_config`**（新表）：`id PK, company_id, channel VARCHAR(16), merchant_no VARCHAR(64), app_id VARCHAR(64), api_key VARCHAR(128), notify_url VARCHAR(255), extra CLOB, status TINYINT DEFAULT 1, created, updated` + `uk_pay_channel(company_id, channel)`。
- 发号：`SeqName` 追加 `OMS_MSG("oms_msg", "OMS")`（队列留痕的 `msg_id`），`sql/schema.sql` 同批补号段行（C166 那条纪律）。
- 权限码从 **71** 起：`71 systemlink.lists`、`72 systemlink.update`、`73 wechat.lists`、`74 wechat.update`、`75 wechat.push`（发模板消息/取码）、目录码 **76 `systemlink_center`**、**77 `wechat_center`**。role 2 一条都不配（对外凭据与推送口子同 §25.5 的划分）。
- 配置：`ecx.systemlink.enabled`=`true`、`ecx.systemlink.require-ip-whitelist`=`${ECX_SYSTEMLINK_REQUIRE_IP:false}`、`ecx.wx.profile`=`${ECX_WX_PROFILE:mock}`（`mock` 不出网、落 `wx_message_log`；`real` 走微信开放平台）、`ecx.wx.api-base`=`https://api.weixin.qq.com`、`ecx.wx.component-app-id`/`ecx.wx.component-app-secret`/`ecx.wx.component-token`/`ecx.wx.component-aes-key`（第三方平台四件套，缺省空）、`ecx.pay.active-channel-profile` 扩展支持 `adapay`/`bspay`/`hfpay`（逗号叠加）。

### 27.6 门槛用例（进 e2e 与冒烟，任一条红就不算交付）

1. **两支签名的差异必须被证出来**：同一份含 `null` 与布尔值的参数，签名 A 与签名 B 算出的串**不同**（手写期望串钉死，不拿产品代码自签自验）；改一个字节 → `sign error`；缺 `sign` → `sign error`；token 换了即刻失效。
2. **IP 白名单在签名之后**：签名错 + IP 不在白名单 → 回 `sign error`（不是 `ip access denied`）；签名对 + IP 不在白名单 → `ip access denied`；白名单空 = 不限制。
3. **留痕两行**：一次成功的 OME 调用落 `request` + `response` 两行，`status=success`、`runtime>=0`、`msg_id` 同值；失败落 `fail` 且 `result` 里有错因；`SL-A3` 列表**不出** `params`/`result`。
4. **四件事的业务口径**：`store.logistics.offline.send` 后订单 `delivery_status=DONE` 且 `orders_delivery` 有一行、再发一次回"请勿重复发货"；`store.trade.status.update` 在 `cancel_status=SUCCESS` 时答 true、否则 `订单信息有误`、且**两种情况订单行都不变**；`store.items.quantity.list.update` 一个不存在的 bn 只进 `no_update`、其余照改；site 四条的 create→checkisopen→renew→close 全链（`expired_at` 从原到期时间加）。
5. **微信侧**：mock profile 下发模板消息落 `wx_message_log`（`mock=1`、`status=success`）且**不出网**（本机无外网也能绿）；`type=unlimit` 取码在 mock 下返回可解析的 PNG 字节头；第三方平台回调的 `component_verify_ticket` 落 `wx_app`（解密失败 → 400 且不落库）；未绑定租户发消息 → 422 且 `wx_message_log` 不落行（"没配置"与"发失败"不是一件事）。
6. **支付三渠道**：三个 profile 各自 `pay()` 出参数包且 `settled=false`；回调验签错 → 控制器 FAIL 且订单不变；验签对 → 结清；`refund()` 回 `pendingCallback`；`queryTrade()` 在模拟服务器上拿到 `SUCCESS` 后补单作业能把订单推进。
7. **不碰既有面**：`mvn -B -o verify` 全绿 + 既有 `m06`/`m11`/`m15`/`m21` 四张冒烟在同库上复跑全绿（新增入站链不改任何既有写路径的行为）。

## 28. 实现期语义补充（M2-2 #82..#86：SystemLink 入站 + 微信开放平台 + 支付渠道实现位落地时确认，追加于 §27 之后，不回改上文）

| ID | 差异 | 证据 |
|---|---|---|
| C212 | **`component_verify_ticket` 不落库**（修正 §27.6 门槛⑤的原话"落 wx_app"）：它是**平台级**凭据、只在进程内换 `component_access_token` 用，而 `wx_app` 是租户级表，放不下它；`wx_app` 落的是**事件**——`authorized`/`updateauthorized`/`unauthorized` 写授权行（status 翻 0/1）。ticket 走进程内 `WxComponentState`（同部署一般只有一个 component_appid，取"最近一次的 ticket"即可），重启丢一次票 = 下一条回调 10 分钟内就补上，微信的原语就是这么设计的 | e2e「ticket 进进程内状态（不落租户表）」「unauthorized 事件把授权行翻停用→重新绑定恢复 status=1」 |
| C213 | **模板注册表在进程内**：源系统的模板存**微信侧**（`template/get_all_private_template` 就是查微信的家底），本项目没有"模板表"这回事；mock 注册表只在进程内（重启即空，下次 `A6` 再建一次就是），真链 profile 的模板增删查直接打微信 | 冒烟/e2e「A5 能看到新模板」；`WxTemplateService` mock 分支注释 |
| C214 | **SL-M2 站点关闭的判据收敛为 `company.status='closed'`**：源系统写的是 `company.is_disabled=1`，本项目沿用 `status` 这一位（'normal'→'closed'），语义同为"不删行，历史订单的归属不断"；`checkisopen` 的判据即 `status=='normal' && 未过期` | e2e「site 全链」——close 后 `checkisopen.is_open` 翻 false；数据仍在 |
| C215 | **三家聚合支付共用一套签名约定的实现位**（"不逐家联调"的落点）：`sign = hex(HMAC-SHA256(api_key, canonical))`，`canonical` = 除 `sign` 外全部参数按 key 字节序排 `k=v&` 连拼（与 SystemLink 两支 assemble 同一"键字节序"的形状）。**换签名公式是逐家联调时的事，但"验签挡在动账之前"这个位次是三家共用的约定，换公式不许把这一位换掉**——和 M1-2 真渠道里"结清归 PayService、验签归渠道"的分工同一条纪律 | `ThirdPartyChannels.kt` 的 `ChannelSign`；订单 `notifyBody` 的手算 sign（e2e 门槛⑥） |
| C216 | **三渠道回执报文形态与状态归一**：回调是 JSON `{"merchant_no","out_trade_no","amount","status","transaction_id",…,"sign"}`；三家原话各不相同（`success`/`paid`/`succeeded`），`parseNotification` 一次收口成 `SUCCESS`/`CLOSED`——渠道原话不进订单表。`out_refund_no` 出现即视为退款回执进行映射；查单只认配置 `extra` 里给了 `query_url` 的形态（e2e 假服务器/仿真网关用），没给就 `null` 退出补单轮询（真链 SDK 逐家联调是 M2-4 的事） | e2e「回调把支付单结清」「错签不动账」「refund() 回 pendingCallback」；`ThirdPartyChannels.kt` 注释 |
| C217 | **`no_update` 语义与"整体不失败"的位次配平**：`store.items.quantity.list.update` 的 `total` 是行列数不是成功数、`updated` 是可识别行的绝对回写数（`setStore` 直接写值，不同比对现库存）、不认识的 bn 只进 `no_update`——一个错的 SKU 不能拖垮九百九十九个（§27.2 SL-M8 的原话），但其**前提**是签名与白名单都过了：这两道闸在留痕之前，挡住的是整单 | e2e「一件可识别的 bn 被绝对回写」「不认识的 bn 进 no_update」「绝对回写不是增量」 |
| C218 | **后建运营 token 恒掩码、留痕在 OME 链独占**：SL-A1/A2 的两把 token 出参永远掩码（`gy_o****_1` 形态），传空=不改；留痕只挂 OME（`ecapi.site.*` 是平台侧对本站的操作，源系统也不记）——这是"哪条链需要排障证据"的分工，不是漏写留痕 | e2e「token 恒掩码」断言（SL-A1 出参形状）；`SystemLinkSettingService` maskToken |
| C219 | **踩坑①（KDoc 内字面量 `/*` 触发嵌套块注释 → 整文件语法错）在本族第三次复现**：注释里的示例路径一律写 `{method}` 占位形态（`/api/systemlink/openapi/{method}`），不写通配串。`mvn -DskipTests` 只跳执行、`test-compile` 仍编译测试源——测试正文里同样不得出字面量通配（e2e 的注释也用 `{method}` 形态，C199 已钉） | `fix(m2-2)` commit d34174f（CI 编译失败现场：`Unclosed comment`）与 §26 踩坑①；本族注释全部改用 `{method}` 形态 |

## 29. 长尾端点与营销扩展规格（M2-3 #87..：逐域小批次交付，本节随批追加，不回改上文）

### 29.1 互动域（签到 / 大转盘）

交付面：前台 **9 条 + 后台 8 条**（`service/promotion/{CheckInService,TurntableService}`）、
三张新表（`user_checkin`/`user_lottery_times`/`user_lottery_log`，基表数 80→83）、
权限码 **78..80**（挂在既有"营销中心"目录 27 下，role 2 一条都不配）。

前台（`/api/h5app/wxapp`，会员 JWT）：

| 方法 | URL | 说明 | 约束 |
|---|---|---|---|
| POST | `/api/h5app/wxapp/sign` | 签到 | 幂等：同天第二次回业务错"今日已签到"（uk(g,u,day) 兜底） |
| GET | `/api/h5app/wxapp/sign/weekly/list` | 周面板 | 近 7 天逐日子项 + 汇总（signed_days/week_point/continuous/总数） |
| POST | `/api/h5app/wxapp/promotion/checkin/create` | 别名=sign | 同一契约、同一幂等判据（D-IC2） |
| GET | `/api/h5app/wxapp/promotion/checkin/getlist` | 别名=weekly/list | 同一契约（D-IC2） |
| GET | `/api/h5app/wxapp/promotion/turntableconfig` | 转盘配置 | 奖品池（id/name/type/point/weight）+ 次数规则 |
| GET | `/api/h5app/wxapp/promotion/getLuckyDrawData` | 转盘状态 | 配置 + `residue`/`drawn_today`/`login_add_claimed` |
| GET | `/api/h5app/wxapp/promotion/turntable` | 抽奖 | **GET 参与抽取照抄源**（write-on-GET 见差异 C221）；次数不足/未开启 422 业务错 |
| GET | `/api/h5app/wxapp/promotion/getLuckyDrawLog` | 我的抽奖流水 | 翻页 `page`/`page_size` |
| GET | `/api/h5app/wxapp/promotion/loginaddtimes` | 登录送次数 | 每日一次 +N；重复领不加、不报错（uk(g,u,day) 冲突即放弃） |

后台（`/api/promotions`，运营 JWT + 权限码）：

| 方法 | URL | 说明 | 权限码 |
|---|---|---|---|
| GET | `/api/promotions/checkin/getlist` | 会员签到记录翻页（`user_id`/`day_start`/`day_end`，0=不设界） | 78 `promotions.checkin.lists` |
| POST / PUT | `/api/promotions/turntableconfig` | 配置覆盖式写（缺字段按缺省）；开启态必须有 weight>0 奖品 | 80 `promotions.turntable.update` |
| GET | `/api/promotions/turntableconfig` | 配置读 | 80 |
| GET | `/api/promotions/getturntableList` | 转盘快照（恒一行） | 79 `promotions.turntable.lists` |
| GET | `/api/promotions/getturntable?id=1` | 详情 | 79 |
| GET | `/api/promotions/getturntable_log/byid` | 全量抽奖流水翻页 | 79 |
| GET | `/api/promotions/getturntable_count/byid` | 汇总：笔数/送分合计/参与人数 | 79 |
| GET | `/api/promotions/down_lucky_draw/byid?id=1` | 下架=关开关，其余配置保原值 | 80（`@Audited`） |

配置与数据模型：

- **签到送分**：走 [PointService.adjust]（全仓唯一"无订单来源"动账口），`remark` 恒为 `签到`；
  键 `checkin.point`（每日基础，缺省 10）与 `checkin.bonus`（第 7/14/…天加成，缺省 20）。
  `continuous` 每行落库（昨天那行的 +1，断签从 1 重来）。
- **送分幂等同事务**：签到行与积分流水在同一事务提交，"行在、分没到"不可能；
  积分域对账恒等式（M1-5）对这批流水用 `remark` 认源。
- **次数是派生值**：`residue = base_times(配置) + add_times(当日领取行) − 当日流水数(总不落列)`。
  `turntable.config`（`sys_config` JSON）：`enable/name/base_times/login_add_times/prizes[{id,name,type(point|none),point,weight}]`。
- **确定性抽取**：Random 种子由 (company,user,day,当日第几抽) 推导，重放同结果；
  真随机让"中积分奖"这条门槛没法稳定钉死。
- 中积分奖同事务落 `user_lottery_log`（`remark=抽奖` 认源），`prize_type=none` 不动分。
- `day` 列一律 yyyymmdd 整数（Asia/Shanghai 业务日界），不是 Unix 秒——唯一键要的是"一天"。

差异表（追加于 C219 之后）：

| ID | 差异 | 理由 |
|---|---|---|
| C220 | **别名口合并**：源系统 `/sign` 族与 `/promotion/checkin/*` 族是两套处理器各写一遍，本项目按同一实现收口（D-IC2）——两份实现两份数据只会让"同天签了两次"在一条路上报错、另一条路上成功 | 一致性 |
| C221 | **write-on-GET 照抄位**：`GET /promotion/turntable` 与 `GET /down_lucky_draw/byid` 是 GET 挂写动作，源系统原样；本项目照抄不改成 POST/PUT——存量调用端（小程序）只认识 GET，发明 REST 版本等于断链 | 兼容 |
| C222 | **多活动收敛为单配置**：源转盘是多活动表（`getturntableList` 列表语义），本项目收敛为每租户一个转盘（`activity_id` 恒 1）；列表回一行快照、`total_count=1`。承诺清单里没有"多期转盘"任何一条，建表就是建清单外的承诺；`activity_id` 列留在流水上，将来真拆多活动流水不用迁 | 最小集 |
| C223 | **抽奖次数三件源**：base 是"人人有份"的配置、add 是当日领取态、当日流水数是已花掉——剩余数是派生值不落列，与积分账户"余额=账头，流水可回放"同构 | 一致性 |

门槛用例（进 `M23EndToEndTest`，独立 H2 `exccm23`）：

1. **签到幂等**：同天第二次 `/sign` → `今日已签到`；送分后的 `point_member_log` 有 `remark=签到` 一行，`Σ change_point == point` 恒等式不破。
2. **连签加成**：第 7 天的行 `point = base + bonus`、`continuous=7`（前 6 天用 seed/逐日置数铺，不靠改时钟）。
3. **别名一致性**：`/promotion/checkin/create` 与 `/sign` 冲突时同回"今日已签到"；`checkin/getlist` 出参与 `sign/weekly/list` 同形状。
4. **抽奖链**：未开启 → 422；开启（配置单奖品 weight 独占）→ 抽中 point 奖 → 流水行 + `remark=抽奖` 送分行 + `residue` 减一；次数耗尽 → 422。
5. **每日加次幂等**：`loginaddtimes` 首 +N、二刷不改值；加次把原来"用完"的转盘救活一抽。
6. **后台留痕**：`getturntable_log/byid` 看得到前台抽的行；`getturntable_count/byid` 三个数与流水一致；下架后前台抽取 422 且配置其余字段不丢。

### 29.2 砍价域

交付面：后台 **6 条** + 前台 **5 条**（`service/promotion/BargainService` + `dao/promotion/BargainMappers.kt`）、
三张新表（`bargain_promotions`/`user_bargain`/`user_bargain_log`，基表数 83→86）、
权限码 **81/82**（同挂营销中心目录 27，role 2 一条都不配）、SecurityConfig 前台链补两条 frontnoauth 白名单位。

后台（JWT + 权限码，写路径 `@Audited`）：

| 方法 | URL | 说明 | 权限码 |
|---|---|---|---|
| POST | `/api/promotions/bargains` | 创建（`item_price` 缺省取商品现价；floor<price、steps≥1 硬校验） | 82 `promotions.bargain.update` |
| GET | `/api/promotions/bargains` | 列表翻页（含停用行） | 81 `promotions.bargain.lists` |
| GET | `/api/promotions/bargains/{bargain_id}` | 详情 | 81 |
| PUT | `/api/promotions/bargains/{bargain_id}` | 编辑（覆盖式；`disabled` 同走这条） | 82 |
| PUT | `/api/promotions/bargains/termination/{bargain_id}` | 终止（停发刀、留行留刀史） | 82 |
| DELETE | `/api/promotions/bargains/{bargain_id}` | 删除（已有会员参与 → 业务错；保护位 D-IC5） | 82 |

前台（`/api/h5app/wxapp/promotion`）：

| 方法 | URL | 鉴权 | 说明 |
|---|---|---|---|
| GET | `/promotion/bargains` | jwt | 未停用活动行（joined_users 聚合） |
| POST | `/promotion/userbargain` | jwt | 参与砍价（body `bargain_id`；每会员每活动一份刀，uk 兜双击） |
| GET | `/promotion/userbargain?id=` | frontnoauth | 详情 + 刀史（分享页照抄位） |
| POST | `/promotion/bargainlog` | frontnoauth | 帮砍一刀（`user_bargain_id`；游客 helper_id=0） |
| GET | `/promotion/bargainfriendwxappcode?user_bargain_id=` | jwt | 委托 WxAcodeService（A9 复用 unlimit，scene=`ub:{id}`），回二进制 PNG |

模型与判据：

- **刀是定长的**：每刀减 `ceil((item_price − floor_price) / steps)` 分，末刀钳到地板价（页面可预告）；
  `reached` 是资格位（after ≤ floor 即翻），`steps_left = steps − 刀史行数` 派生不落列。
- **uk(company,bargain,user)** = "同一活动同一会员一份刀"；重复参与回业务错"已参与过该砍价"。
- **删除守卫**：有 `user_bargain` 在 → 删除拒绝（"已有用户参与，不能删除（可终止下架）"），终止是下架位。
- 成单链路（按底价下单）**不接**（C224）：`order_class='bargain'` 已在 schema 预留，接入单独立批。

差异表（§29.2 追加）：

| ID | 差异 | 理由 |
|---|---|---|
| C224 | **成单链路不接**：源系统砍到底可"按底价下单"，是把快照参数送进下单链的一跳；本批承诺位到"资格位"为止，接入要动 cart/checkout，单独立批 | 最小集 |
| C225 | **刀长定量化**：源系统每刀金额带随机成分（砍价表格配置区间）；本项目定长 ceil，随机性留给抽奖域（两域不同味，可解释性优先） | 可解释性 |
| C226 | **friend 幂等取宽**：同一好友重复帮砍不拦（刀数上限托底）；源系统进店维度的判重依赖登录态，frontnoauth 语义下没有可靠的判重键 | 取宽 |

门槛用例（并入 `M23EndToEndTest` @Order(9)..）：

1. **后台 CRUD+终止**：建（floor≥price → 422；steps=0 → 422）→ 列表/详情 → 终止翻 `disabled` → 前台列表消失。
2. **参与+帮砍全链**：参与（current=原价、steps_left=10）→ 重复参与 422 → 帮砍 10 刀（定长 890 分）→ 第 10 刀后 `reached`、current=底价 → 再砍 422"已砍到底价"；每刀 `user_bargain_log` 一行。
3. **分享码**：GET PNG → 字节头 89 50 4E 47（mock 不出网）。
4. **删除守卫**：参与过的活动删除 → 422；无参与的新建+删除 → `deleted=true`。
### 29.3 加价购扩展与人群折扣（本批后补）
### 29.4 次卡 / rights 族（本批后补）
### 29.5 浏览 / 搜索增强与遗留 method（本批后补）
### 29.6 M2-4 生产化（本批后补）
