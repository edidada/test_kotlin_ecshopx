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
