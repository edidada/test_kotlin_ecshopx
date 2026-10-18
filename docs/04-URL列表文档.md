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
