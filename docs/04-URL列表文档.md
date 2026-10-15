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
