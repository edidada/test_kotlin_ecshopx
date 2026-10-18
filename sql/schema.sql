-- =====================================================================
-- kotlin-ecshopx 部署建表脚本（H2, MODE=MySQL）
-- 来源：ECShopX PHP 源码 Doctrine 实体注解逆向整理（thirdparty/ECShopX/src/*Bundle/Entities）
-- 约定：
--   * 金额一律 BIGINT，单位=分（与 PHP 侧一致）
--   * 时间一律 BIGINT，Unix 秒（created/updated 对应 PHP Gedmo Timestampable）
--   * 主键沿用 PHP 侧业务命名（item_id/order_id/user_id…），业务单号列由应用层号段生成器产生
--   * 无物理外键（与源系统一致，均为逻辑外键 + 显式索引）
--   * JSON 配置/数组列统一为 CLOB（应用层序列化）
--   * 多租户列 company_id 全表保留
-- 使用（H2）：
--   java -cp h2-2.x.jar org.h2.tools.RunScript \
--        -url "jdbc:h2:./data/ecshopx;MODE=MySQL" -user sa -script sql/schema.sql
--   Spring Boot 亦可自动执行：spring.sql.init.mode=always + schema/data 指向本文件
-- =====================================================================

-- 注：不再显式建/切 ECX schema —— SET SCHEMA 仅作用于当前会话，连接池其余连接仍落默认
--     schema（实测 H2 2.2.224 池外新连接解析不到表）。MySQL 部署选库即等效；H2 走 public。

-- =====================================================================
-- 1. 平台 / 租户 / 权限域（对应 CompanysBundle + SuperAdminBundle 核心表）
-- =====================================================================

CREATE TABLE IF NOT EXISTS company (
  company_id       BIGINT AUTO_INCREMENT PRIMARY KEY,
  company_name     VARCHAR(255) NOT NULL,
  license_code     VARCHAR(64),
  status           VARCHAR(16) NOT NULL DEFAULT 'normal',   -- normal/closed
  system_is_saas   BOOLEAN NOT NULL DEFAULT FALSE,
  created          BIGINT NOT NULL DEFAULT 0,
  updated          BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS operators (
  operator_id      BIGINT AUTO_INCREMENT PRIMARY KEY,
  company_id       BIGINT NOT NULL,
  username         VARCHAR(64) NOT NULL,
  password         VARCHAR(255) NOT NULL,
  mobile           VARCHAR(32),
  operator_type    VARCHAR(32) NOT NULL DEFAULT 'staff',    -- admin/staff/distributor/supplier/merchant
  distributor_ids  CLOB,                                    -- JSON: 绑定的店铺集合
  disabled         BOOLEAN NOT NULL DEFAULT FALSE,
  last_login_time  BIGINT,
  created          BIGINT NOT NULL DEFAULT 0,
  updated          BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_operators_company ON operators(company_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_operators_username ON operators(company_id, username);

CREATE TABLE IF NOT EXISTS roles (
  role_id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  company_id       BIGINT NOT NULL,
  role_name        VARCHAR(64) NOT NULL,
  role_type        VARCHAR(32) DEFAULT 'custom',            -- system/custom
  created          BIGINT NOT NULL DEFAULT 0,
  updated          BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS resources (
  resource_id      BIGINT AUTO_INCREMENT PRIMARY KEY,
  parent_id        BIGINT NOT NULL DEFAULT 0,
  resource_name    VARCHAR(64) NOT NULL,
  resource_code    VARCHAR(128),                            -- 对应路由别名 as，如 goods.items.lists
  resource_type    VARCHAR(16) NOT NULL DEFAULT 'actived',  -- catalog/actived
  sort             INT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS operator_rel_roles (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  operator_id      BIGINT NOT NULL,
  role_id          BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_orr_operator ON operator_rel_roles(operator_id);

CREATE TABLE IF NOT EXISTS role_rel_resources (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  role_id          BIGINT NOT NULL,
  resource_id      BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_rrr_role ON role_rel_resources(role_id);

CREATE TABLE IF NOT EXISTS operator_logs (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  company_id       BIGINT NOT NULL,
  operator_id      BIGINT,
  operator_name    VARCHAR(64),
  route_name       VARCHAR(128),
  method           VARCHAR(64),
  params           CLOB,
  ip               VARCHAR(64),
  created          BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_oplog_company ON operator_logs(company_id, created);

-- =====================================================================
-- 2. 会员域（MembersBundle / PointBundle / DepositBundle）
-- =====================================================================

CREATE TABLE IF NOT EXISTS members (
  user_id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  company_id       BIGINT NOT NULL,
  grade_id         BIGINT NOT NULL DEFAULT 0,
  mobile           VARCHAR(32),
  password         VARCHAR(255),
  login_email      VARCHAR(128),
  user_card_code   VARCHAR(32),
  inviter_id       BIGINT NOT NULL DEFAULT 0,
  source_from      VARCHAR(32),
  source_id        VARCHAR(64),
  monitor_id       VARCHAR(64),
  authorizer_appid VARCHAR(64),
  wxa_appid        VARCHAR(64),
  disabled         BOOLEAN NOT NULL DEFAULT FALSE,
  use_point        BOOLEAN NOT NULL DEFAULT TRUE,
  created          BIGINT NOT NULL DEFAULT 0,
  updated          BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_members_company ON members(company_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_members_mobile ON members(company_id, mobile);

CREATE TABLE IF NOT EXISTS members_info (
  user_id          BIGINT PRIMARY KEY,
  username         VARCHAR(128),
  name             VARCHAR(64),
  avatar           VARCHAR(512),
  sex              SMALLINT NOT NULL DEFAULT 0,             -- 0未知1男2女
  birthday         VARCHAR(32),
  address          VARCHAR(255),
  email            VARCHAR(128),
  industry         VARCHAR(64),
  income           VARCHAR(64),
  edu_background   VARCHAR(64),
  habbit           CLOB,                                    -- JSON
  have_consume     BOOLEAN NOT NULL DEFAULT FALSE,
  custom_data      CLOB                                     -- JSON: 租户自定义字段
);

CREATE TABLE IF NOT EXISTS members_grade (
  grade_id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  company_id       BIGINT NOT NULL,
  grade_name       VARCHAR(64) NOT NULL,
  level            INT NOT NULL DEFAULT 1,
  growth_value     BIGINT NOT NULL DEFAULT 0,               -- 升级门槛(分)
  discount         INT NOT NULL DEFAULT 100,                -- 折扣百分比
  created          BIGINT NOT NULL DEFAULT 0,
  updated          BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS members_address (
  address_id       BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id          BIGINT NOT NULL,
  company_id       BIGINT NOT NULL,
  username         VARCHAR(50) NOT NULL,                    -- 收货人
  telephone        VARCHAR(20) NOT NULL,
  province         VARCHAR(64),
  city             VARCHAR(64),
  county           VARCHAR(64),
  adrdetail        VARCHAR(255),
  postal_code      VARCHAR(20),
  is_def           BOOLEAN NOT NULL DEFAULT FALSE,
  lng              VARCHAR(32),
  lat              VARCHAR(32),
  created          BIGINT NOT NULL DEFAULT 0,
  updated          BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_addr_user ON members_address(user_id);

CREATE TABLE IF NOT EXISTS members_associations (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id          BIGINT NOT NULL,
  company_id       BIGINT NOT NULL,
  unionid          VARCHAR(128),
  open_id          VARCHAR(128),
  user_type        VARCHAR(32) NOT NULL                     -- wechat/ali/app…
);
CREATE INDEX IF NOT EXISTS idx_assoc_user ON members_associations(user_id);
CREATE INDEX IF NOT EXISTS idx_assoc_union ON members_associations(unionid);

CREATE TABLE IF NOT EXISTS members_items_fav (
  fav_id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id          BIGINT NOT NULL,
  company_id       BIGINT NOT NULL,
  item_id          BIGINT NOT NULL,
  item_name        VARCHAR(100),
  item_image       VARCHAR(512),
  item_price       BIGINT,
  created          BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_fav_user ON members_items_fav(user_id);

CREATE TABLE IF NOT EXISTS members_browse_history (
  history_id       BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id          BIGINT NOT NULL,
  item_id          BIGINT NOT NULL,
  company_id       BIGINT NOT NULL,
  created          BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_browse_user ON members_browse_history(user_id);

CREATE TABLE IF NOT EXISTS point_member (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  company_id       BIGINT NOT NULL,
  user_id          BIGINT NOT NULL,
  point            BIGINT NOT NULL DEFAULT 0,               -- 可用积分
  total_point      BIGINT NOT NULL DEFAULT 0,
  updated          BIGINT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_point_user ON point_member(company_id, user_id);

CREATE TABLE IF NOT EXISTS point_member_log (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  company_id       BIGINT NOT NULL,
  user_id          BIGINT NOT NULL,
  order_id         VARCHAR(64),
  change_point     BIGINT NOT NULL,                         -- 正=得 负=扣
  before_point     BIGINT NOT NULL,
  after_point      BIGINT NOT NULL,
  remark           VARCHAR(255),
  created          BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_ptlog_user ON point_member_log(user_id, created);

CREATE TABLE IF NOT EXISTS user_deposit (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  company_id       BIGINT NOT NULL,
  user_id          BIGINT NOT NULL,
  deposit_amount   BIGINT NOT NULL DEFAULT 0,               -- 余额(分)
  gift_amount      BIGINT NOT NULL DEFAULT 0,               -- 赠金(分)
  total_recharge   BIGINT NOT NULL DEFAULT 0,
  updated          BIGINT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_deposit_user ON user_deposit(company_id, user_id);

CREATE TABLE IF NOT EXISTS deposit_trade (
  trade_id         VARCHAR(64) PRIMARY KEY,                 -- CZ/TD 前缀业务单号
  company_id       BIGINT NOT NULL,
  user_id          BIGINT NOT NULL,
  trade_type       VARCHAR(16) NOT NULL,                    -- recharge/consume/refund
  rel_id           VARCHAR(64),                             -- 关联订单号等
  amount           BIGINT NOT NULL,                         -- 动账金额(分)
  gift_amount      BIGINT NOT NULL DEFAULT 0,
  before_balance   BIGINT NOT NULL,
  after_balance    BIGINT NOT NULL,
  status           VARCHAR(16) NOT NULL DEFAULT 'NOTPAY',
  created          BIGINT NOT NULL DEFAULT 0,
  updated          BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_dtrade_user ON deposit_trade(user_id, created);

CREATE TABLE IF NOT EXISTS deposit_recharge_rule (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  company_id       BIGINT NOT NULL,
  rule_name        VARCHAR(64),
  recharge_amount  BIGINT NOT NULL,                         -- 充值(分)
  gift_amount      BIGINT NOT NULL DEFAULT 0,               -- 赠送(分)
  status           SMALLINT NOT NULL DEFAULT 1,
  created          BIGINT NOT NULL DEFAULT 0,
  updated          BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS members_invoices (
  invoices_id      BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id          BIGINT NOT NULL,
  company_id       BIGINT NOT NULL,
  invoices_type    VARCHAR(15) NOT NULL,                    -- personal/corporate
  name             VARCHAR(128),
  telephone        VARCHAR(32),
  tax_number       VARCHAR(64),
  business_address VARCHAR(255),
  bank             VARCHAR(128),
  bank_account     VARCHAR(64),
  is_def           BOOLEAN NOT NULL DEFAULT FALSE,
  created          BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_inv_user ON members_invoices(user_id);

-- =====================================================================
-- 3. 商品域（GoodsBundle）
-- =====================================================================

CREATE TABLE IF NOT EXISTS items (
  item_id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  company_id       BIGINT NOT NULL,
  goods_id         BIGINT NOT NULL DEFAULT 0,               -- SPU id，0=自身
  default_item_id  BIGINT NOT NULL DEFAULT 0,
  is_default       BOOLEAN NOT NULL DEFAULT TRUE,
  item_type        VARCHAR(15) NOT NULL DEFAULT 'normal',   -- normal/services
  item_category    VARCHAR(32),
  consume_type     VARCHAR(32),
  item_name        VARCHAR(255) NOT NULL,
  item_bn          VARCHAR(255),                            -- 货号
  brief            VARCHAR(512),
  price            BIGINT NOT NULL DEFAULT 0,               -- 分
  cost_price       BIGINT NOT NULL DEFAULT 0,
  market_price     BIGINT NOT NULL DEFAULT 0,
  store            BIGINT NOT NULL DEFAULT 0,               -- 库存
  warning_store    BIGINT NOT NULL DEFAULT 0,               -- 预警库存
  sales            BIGINT NOT NULL DEFAULT 0,
  item_unit        VARCHAR(32),
  pics             CLOB,                                    -- JSON 图片数组
  videos           CLOB,
  intro            CLOB,                                    -- 富文本详情
  nospec           BOOLEAN NOT NULL DEFAULT TRUE,           -- 单规格
  approve_status   VARCHAR(32) NOT NULL DEFAULT 'instock',  -- onsale/offline_sale/instock
  audit_status     VARCHAR(32) NOT NULL DEFAULT 'submitting',
  distributor_id   BIGINT NOT NULL DEFAULT 0,               -- 0=商城总部
  supplier_id      BIGINT NOT NULL DEFAULT 0,
  item_source      VARCHAR(32) NOT NULL DEFAULT 'mall',     -- mall/distributor/openapi
  templates_id     BIGINT NOT NULL DEFAULT 0,               -- 运费模板
  is_point         BOOLEAN NOT NULL DEFAULT FALSE,
  point            BIGINT NOT NULL DEFAULT 0,               -- 积分价
  rebate           BIGINT NOT NULL DEFAULT 0,               -- 佣金(分)
  rebate_conf      CLOB,
  is_gift          BOOLEAN NOT NULL DEFAULT FALSE,
  is_medicine      SMALLINT NOT NULL DEFAULT 0,
  is_prescription  SMALLINT NOT NULL DEFAULT 0,
  type             INT NOT NULL DEFAULT 0,                  -- 0普通 1跨境
  tax_rate         INT,
  weight           DOUBLE PRECISION,
  volume           DOUBLE PRECISION,
  brand_id         BIGINT NOT NULL DEFAULT 0,
  sort             INT NOT NULL DEFAULT 0,
  created          BIGINT NOT NULL DEFAULT 0,
  updated          BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_items_company ON items(company_id, approve_status);
CREATE INDEX IF NOT EXISTS idx_items_goods ON items(goods_id);
CREATE INDEX IF NOT EXISTS idx_items_distributor ON items(distributor_id);
CREATE INDEX IF NOT EXISTS idx_items_bn ON items(item_bn);

CREATE TABLE IF NOT EXISTS items_category (
  category_id      BIGINT AUTO_INCREMENT PRIMARY KEY,
  company_id       BIGINT NOT NULL,
  shop_id          BIGINT NOT NULL DEFAULT 0,
  parent_id        BIGINT NOT NULL DEFAULT 0,
  category_name    VARCHAR(128) NOT NULL,
  category_level   INT NOT NULL DEFAULT 1,
  path             VARCHAR(255),                            -- 物化路径 如 0-1-5-
  is_main_category BOOLEAN NOT NULL DEFAULT FALSE,
  is_show_front    SMALLINT NOT NULL DEFAULT 1,
  commission_ratio INT NOT NULL DEFAULT 0,
  invoice_tax_rate VARCHAR(16),
  distributor_id   BIGINT NOT NULL DEFAULT 0,
  regionauth_id    BIGINT NOT NULL DEFAULT 0,
  sort             INT NOT NULL DEFAULT 0,
  created          BIGINT NOT NULL DEFAULT 0,
  updated          BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_cat_company ON items_category(company_id, parent_id);

CREATE TABLE IF NOT EXISTS goods_items_rel_cats (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  item_id          BIGINT NOT NULL,
  category_id      BIGINT NOT NULL,
  company_id       BIGINT NOT NULL,
  created          BIGINT NOT NULL DEFAULT 0,
  updated          BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_relcats_item ON goods_items_rel_cats(item_id);
CREATE INDEX IF NOT EXISTS idx_relcats_cat ON goods_items_rel_cats(category_id);

CREATE TABLE IF NOT EXISTS items_attributes (
  attribute_id     BIGINT AUTO_INCREMENT PRIMARY KEY,
  company_id       BIGINT NOT NULL,
  shop_id          BIGINT NOT NULL DEFAULT 0,
  attribute_type   VARCHAR(15) NOT NULL,                    -- unit/brand/item_param/spec…
  attribute_name   VARCHAR(128) NOT NULL,
  attribute_show   VARCHAR(15) DEFAULT 'select',
  attribute_code   VARCHAR(32),                             -- OMS 映射
  distributor_id   BIGINT NOT NULL DEFAULT 0,
  sort             INT NOT NULL DEFAULT 0,
  created          BIGINT NOT NULL DEFAULT 0,
  updated          BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS items_attribute_values (
  attribute_value_id BIGINT AUTO_INCREMENT PRIMARY KEY,
  attribute_id     BIGINT NOT NULL,
  company_id       BIGINT NOT NULL,
  shop_id          BIGINT NOT NULL DEFAULT 0,
  attribute_value  VARCHAR(255),
  oms_value_id     VARCHAR(64),
  sort             INT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_attrval_attr ON items_attribute_values(attribute_id);

CREATE TABLE IF NOT EXISTS items_rel_attributes (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  item_id          BIGINT NOT NULL,
  attribute_id     BIGINT NOT NULL,
  attribute_type   VARCHAR(15),
  attribute_value_id BIGINT,
  custom_attribute_value VARCHAR(255),
  image_url        CLOB,
  company_id       BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_relattr_item ON items_rel_attributes(item_id);

CREATE TABLE IF NOT EXISTS items_barcode (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  item_id          BIGINT NOT NULL,
  barcode          VARCHAR(50) NOT NULL,
  default_item_id  BIGINT NOT NULL DEFAULT 0,
  company_id       BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_barcode ON items_barcode(barcode);

CREATE TABLE IF NOT EXISTS items_tags (
  tag_id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  company_id       BIGINT NOT NULL,
  tag_name         VARCHAR(50) NOT NULL,
  tag_color        VARCHAR(16),
  font_color       VARCHAR(16),
  distributor_id   BIGINT NOT NULL DEFAULT 0,
  front_show       SMALLINT NOT NULL DEFAULT 0,
  created          BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS items_rel_tags (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  tag_id           BIGINT NOT NULL,
  item_id          BIGINT NOT NULL,
  company_id       BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_reltags_item ON items_rel_tags(item_id);

CREATE TABLE IF NOT EXISTS items_rel_type (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  item_id          BIGINT NOT NULL,
  label_id         BIGINT NOT NULL,                         -- servicelabels.label_id
  label_name       VARCHAR(128),
  label_price      BIGINT NOT NULL DEFAULT 0,               -- 分
  num_type         VARCHAR(15),                             -- plus/minux/multiply
  num              BIGINT NOT NULL DEFAULT 0,
  is_not_limit_num INT NOT NULL DEFAULT 1,
  limit_time       BIGINT NOT NULL DEFAULT 0                -- 有效天数
);
CREATE INDEX IF NOT EXISTS idx_reltype_item ON items_rel_type(item_id);

CREATE TABLE IF NOT EXISTS servicelabels (
  label_id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  company_id       BIGINT NOT NULL,
  label_name       VARCHAR(128) NOT NULL,
  label_price      BIGINT NOT NULL DEFAULT 0,               -- 分
  service_type     VARCHAR(32),                             -- point/deposit…
  created          BIGINT NOT NULL DEFAULT 0,
  updated          BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS shipping_templates (
  template_id      BIGINT AUTO_INCREMENT PRIMARY KEY,
  company_id       BIGINT NOT NULL,
  name             VARCHAR(50) NOT NULL,
  is_free          VARCHAR(16) NOT NULL DEFAULT 'n',
  valuation        VARCHAR(16) NOT NULL DEFAULT '1',        -- 1按件 2按重 3按体积
  protect_rate     DECIMAL(10,4),
  minprice         DECIMAL(14,2),
  status           BOOLEAN NOT NULL DEFAULT TRUE,
  fee_conf         CLOB,                                    -- JSON 运费规则
  nopost_conf      CLOB,
  free_conf        CLOB,
  distributor_id   BIGINT NOT NULL DEFAULT 0,
  supplier_id      BIGINT NOT NULL DEFAULT 0,
  created          BIGINT NOT NULL DEFAULT 0,
  updated          BIGINT NOT NULL DEFAULT 0
);

-- =====================================================================
-- 4. 购物车 / 订单 / 发货域（OrdersBundle）
-- =====================================================================

CREATE TABLE IF NOT EXISTS orders_cart (
  cart_id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  company_id       BIGINT NOT NULL,
  user_id          BIGINT NOT NULL,
  user_ident       VARCHAR(50),                             -- 未登录 session 标识
  shop_type        VARCHAR(16),                             -- distributor/shop/community
  shop_id          BIGINT NOT NULL DEFAULT 0,
  activity_type    VARCHAR(32),
  activity_id      BIGINT NOT NULL DEFAULT 0,
  marketing_type   VARCHAR(32),
  marketing_id     BIGINT NOT NULL DEFAULT 0,
  item_type        VARCHAR(15) NOT NULL DEFAULT 'normal',
  item_id          BIGINT NOT NULL,
  items_id         VARCHAR(255),                            -- 组合商品 item 列表
  item_name        VARCHAR(255),
  pics             VARCHAR(1024),
  num              BIGINT NOT NULL DEFAULT 1,
  price            BIGINT NOT NULL DEFAULT 0,               -- 加入时单价(分)
  point            BIGINT NOT NULL DEFAULT 0,
  is_checked       BOOLEAN NOT NULL DEFAULT TRUE,
  is_plus_buy      BOOLEAN NOT NULL DEFAULT FALSE,
  source_type      VARCHAR(32),
  promoter_user_id BIGINT NOT NULL DEFAULT 0,
  created          BIGINT NOT NULL DEFAULT 0,
  updated          BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_cart_user ON orders_cart(user_id, shop_id);

CREATE TABLE IF NOT EXISTS orders_normal_orders (
  order_id         BIGINT PRIMARY KEY,                      -- 应用层号段生成
  title            VARCHAR(255),
  company_id       BIGINT NOT NULL,
  shop_id          BIGINT NOT NULL DEFAULT 0,
  user_id          BIGINT NOT NULL,
  act_id           BIGINT NOT NULL DEFAULT 0,
  mobile           VARCHAR(32),
  order_class      VARCHAR(32) NOT NULL DEFAULT 'normal',   -- normal/groups/seckill/bargain…
  order_type       VARCHAR(32) NOT NULL DEFAULT 'normal',
  order_status     VARCHAR(32) NOT NULL DEFAULT 'NOTPAY',   -- NOTPAY/PAYED/PACKING/DONE/CANCEL…
  pay_status       VARCHAR(32) NOT NULL DEFAULT 'NOTPAY',
  delivery_status  VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  cancel_status    VARCHAR(32) NOT NULL DEFAULT 'NOCANCEL',
  order_source     VARCHAR(32),
  order_holder     VARCHAR(32),
  order_from       VARCHAR(32),                             -- pc/h5/wxapp/aliapp
  item_fee         BIGINT NOT NULL DEFAULT 0,               -- 商品总额(分)
  total_fee        BIGINT NOT NULL DEFAULT 0,               -- 应付总额(分)
  market_fee       BIGINT NOT NULL DEFAULT 0,
  cost_fee         BIGINT NOT NULL DEFAULT 0,
  commission_fee   BIGINT NOT NULL DEFAULT 0,
  freight_fee      BIGINT NOT NULL DEFAULT 0,               -- 运费(分)
  discount_fee     BIGINT NOT NULL DEFAULT 0,               -- 活动优惠(分)
  member_discount  BIGINT NOT NULL DEFAULT 0,
  coupon_discount  BIGINT NOT NULL DEFAULT 0,
  point_fee        BIGINT NOT NULL DEFAULT 0,               -- 积分抵扣金额(分)
  point_use        BIGINT NOT NULL DEFAULT 0,               -- 使用积分数
  item_point       BIGINT NOT NULL DEFAULT 0,
  get_points       BIGINT NOT NULL DEFAULT 0,               -- 订单可得积分
  total_rebate     BIGINT NOT NULL DEFAULT 0,               -- 佣金合计(分)
  distributor_id   BIGINT NOT NULL DEFAULT 0,
  salesman_id      BIGINT NOT NULL DEFAULT 0,
  supplier_id      BIGINT NOT NULL DEFAULT 0,
  merchant_id      BIGINT NOT NULL DEFAULT 0,
  original_order_id BIGINT NOT NULL DEFAULT 0,              -- 拆单前主单
  type             INT NOT NULL DEFAULT 0,                  -- 0普通 1跨境
  taxable_fee      BIGINT NOT NULL DEFAULT 0,
  total_tax        BIGINT NOT NULL DEFAULT 0,
  fee_type         VARCHAR(5),
  fee_rate         DOUBLE PRECISION,
  fee_symbol       VARCHAR(8),
  pay_type         VARCHAR(32),
  pay_channel      VARCHAR(32),
  receipt_type     VARCHAR(16),                             -- logistics/ziti
  ziti_code        VARCHAR(64),
  ziti_status      VARCHAR(16),
  invoice          CLOB,
  is_invoiced      BOOLEAN NOT NULL DEFAULT FALSE,
  invoice_status   VARCHAR(32),
  auto_cancel_time BIGINT,
  auto_finish_time BIGINT,
  end_time         BIGINT,
  delivery_time    BIGINT,
  delivery_corp    VARCHAR(64),
  delivery_code    VARCHAR(64),
  receiver_name    VARCHAR(128),
  receiver_mobile  VARCHAR(500),
  receiver_state   VARCHAR(64),
  receiver_city    VARCHAR(64),
  receiver_district VARCHAR(64),
  receiver_address CLOB,
  is_rate          BOOLEAN NOT NULL DEFAULT FALSE,
  left_aftersales_num INT NOT NULL DEFAULT 0,
  source_from      VARCHAR(32),
  is_online_order  BOOLEAN NOT NULL DEFAULT TRUE,
  remark           CLOB,
  created          BIGINT NOT NULL DEFAULT 0,
  updated          BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_orders_user ON orders_normal_orders(user_id, order_status);
CREATE INDEX IF NOT EXISTS idx_orders_company ON orders_normal_orders(company_id, created);
CREATE INDEX IF NOT EXISTS idx_orders_distributor ON orders_normal_orders(distributor_id, pay_status);

CREATE TABLE IF NOT EXISTS orders_normal_orders_items (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_id         BIGINT NOT NULL,
  company_id       BIGINT NOT NULL,
  user_id          BIGINT NOT NULL,
  goods_id         BIGINT NOT NULL DEFAULT 0,
  item_id          BIGINT NOT NULL,
  item_bn          VARCHAR(255),
  goods_bn         VARCHAR(255),
  shop_id          BIGINT NOT NULL DEFAULT 0,
  distributor_id   BIGINT NOT NULL DEFAULT 0,
  act_id           BIGINT NOT NULL DEFAULT 0,
  item_name        VARCHAR(255),
  pic              VARCHAR(512),
  num              BIGINT NOT NULL DEFAULT 1,
  price            BIGINT NOT NULL DEFAULT 0,               -- 成交单价(分)
  total_fee        BIGINT NOT NULL DEFAULT 0,
  item_fee         BIGINT NOT NULL DEFAULT 0,
  cost_fee         BIGINT NOT NULL DEFAULT 0,
  commission_fee   BIGINT NOT NULL DEFAULT 0,
  market_price     BIGINT NOT NULL DEFAULT 0,
  cost_price       BIGINT NOT NULL DEFAULT 0,
  rebate           BIGINT NOT NULL DEFAULT 0,
  total_rebate     BIGINT NOT NULL DEFAULT 0,
  member_discount  BIGINT NOT NULL DEFAULT 0,
  coupon_discount  BIGINT NOT NULL DEFAULT 0,
  discount_fee     BIGINT NOT NULL DEFAULT 0,
  discount_info    CLOB,
  order_item_type  VARCHAR(16) DEFAULT 'normal',            -- normal/gift/plus_buy
  delivery_status  VARCHAR(32),
  delivery_time    BIGINT,
  aftersales_status VARCHAR(32),
  refunded_fee     BIGINT NOT NULL DEFAULT 0,
  templates_id     BIGINT NOT NULL DEFAULT 0,
  is_total_store   BOOLEAN NOT NULL DEFAULT TRUE,
  item_point       BIGINT NOT NULL DEFAULT 0,
  point            BIGINT NOT NULL DEFAULT 0,
  point_fee        BIGINT NOT NULL DEFAULT 0,
  share_points     BIGINT NOT NULL DEFAULT 0,
  item_spec_desc   VARCHAR(255),
  tax_rate         VARCHAR(16),
  cross_border_tax BIGINT NOT NULL DEFAULT 0,
  weight           DOUBLE PRECISION,
  volume           DOUBLE PRECISION,
  is_invoice       INT NOT NULL DEFAULT 0,
  cancel_item_num  INT NOT NULL DEFAULT 0,
  delivery_item_num INT NOT NULL DEFAULT 0,
  created          BIGINT NOT NULL DEFAULT 0,
  updated          BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_orderitems_order ON orders_normal_orders_items(order_id);
CREATE INDEX IF NOT EXISTS idx_orderitems_item ON orders_normal_orders_items(item_id);

CREATE TABLE IF NOT EXISTS orders_associations (
  order_id         BIGINT PRIMARY KEY,                      -- 路由单号(含子类订单)
  company_id       BIGINT NOT NULL,
  user_id          BIGINT NOT NULL,
  order_class      VARCHAR(32),
  order_type       VARCHAR(32),
  order_status     VARCHAR(32),
  cancel_status    VARCHAR(32),
  delivery_status  VARCHAR(32),
  total_fee        BIGINT NOT NULL DEFAULT 0,
  member_discount  BIGINT NOT NULL DEFAULT 0,
  coupon_discount  BIGINT NOT NULL DEFAULT 0,
  total_rebate     BIGINT NOT NULL DEFAULT 0,
  is_distribution  BOOLEAN NOT NULL DEFAULT FALSE,
  promoter_user_id BIGINT NOT NULL DEFAULT 0,
  source_id        VARCHAR(64),
  monitor_id       VARCHAR(64),
  salesman_id      BIGINT NOT NULL DEFAULT 0,
  wxa_appid        VARCHAR(64),
  authorizer_appid VARCHAR(64),
  end_time         BIGINT,
  created          BIGINT NOT NULL DEFAULT 0,
  updated          BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_assoc_order_user ON orders_associations(user_id, order_status);

CREATE TABLE IF NOT EXISTS orders_rel_promotions (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  moid             BIGINT NOT NULL,                         -- 主订单号
  coid             BIGINT NOT NULL DEFAULT 0,               -- 子订单号
  user_id          BIGINT NOT NULL,
  order_type       VARCHAR(32),
  item_id          BIGINT NOT NULL DEFAULT 0,
  item_name        VARCHAR(255),
  item_type        VARCHAR(32),
  activity_id      BIGINT NOT NULL DEFAULT 0,
  activity_name    VARCHAR(128),
  activity_type    VARCHAR(32),
  discount_fee     BIGINT NOT NULL DEFAULT 0,
  status           VARCHAR(16) NOT NULL DEFAULT 'valid',    -- valid/invalid
  created          BIGINT NOT NULL DEFAULT 0,
  updated          BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_relpro_order ON orders_rel_promotions(moid);

CREATE TABLE IF NOT EXISTS orders_operate_logs (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_id         BIGINT NOT NULL,
  operator_id      BIGINT,
  operator         VARCHAR(64),
  operator_role    VARCHAR(32),                             -- buyer/seller/system
  behavior         VARCHAR(32),                             -- create/payed/delivery/cancel…
  log_text         CLOB,
  created          BIGINT NOT NULL DEFAULT 0,
  updated          BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_oplog_order ON orders_operate_logs(order_id, created);

CREATE TABLE IF NOT EXISTS orders_cancel_orders (
  cancel_id        BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_id         BIGINT NOT NULL,
  company_id       BIGINT NOT NULL,
  user_id          BIGINT NOT NULL,
  shop_id          BIGINT NOT NULL DEFAULT 0,
  distributor_id   BIGINT NOT NULL DEFAULT 0,
  supplier_id      BIGINT NOT NULL DEFAULT 0,
  total_fee        BIGINT NOT NULL DEFAULT 0,
  progress         SMALLINT NOT NULL DEFAULT 0,             -- 0待处理1已取消2处理中3拒绝
  cancel_from      VARCHAR(32),
  cancel_reason    VARCHAR(300),
  shop_reject_reason VARCHAR(300),
  refund_status    VARCHAR(32),
  point            BIGINT NOT NULL DEFAULT 0,
  pay_type         VARCHAR(32),
  created          BIGINT NOT NULL DEFAULT 0,
  updated          BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_cancel_order ON orders_cancel_orders(order_id);

CREATE TABLE IF NOT EXISTS orders_delivery (
  orders_delivery_id BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_id         BIGINT NOT NULL,
  company_id       BIGINT NOT NULL,
  supplier_id      BIGINT NOT NULL DEFAULT 0,
  delivery_corp    VARCHAR(64),
  delivery_corp_name VARCHAR(64),
  delivery_code    VARCHAR(64),                             -- 运单号
  package_type     VARCHAR(16),                             -- batch/sep
  delivery_time    BIGINT,
  delivery_pics    CLOB,
  created          BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_delivery_order ON orders_delivery(order_id);

CREATE TABLE IF NOT EXISTS orders_delivery_items (
  orders_delivery_items_id BIGINT AUTO_INCREMENT PRIMARY KEY,
  orders_delivery_id BIGINT NOT NULL,
  order_id         BIGINT NOT NULL,
  order_items_id   BIGINT NOT NULL,
  item_id          BIGINT NOT NULL,
  num              BIGINT NOT NULL DEFAULT 1                -- 本次发货数量
);
CREATE INDEX IF NOT EXISTS idx_delitems_delivery ON orders_delivery_items(orders_delivery_id);

CREATE TABLE IF NOT EXISTS trade_rate (
  rate_id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  company_id       BIGINT NOT NULL,
  order_id         VARCHAR(64) NOT NULL,
  item_id          BIGINT NOT NULL,
  goods_id         BIGINT NOT NULL DEFAULT 0,
  user_id          BIGINT NOT NULL,
  star             INT NOT NULL DEFAULT 1,
  content          CLOB,
  rate_pic         CLOB,
  anonymous        BOOLEAN NOT NULL DEFAULT FALSE,
  is_reply         BOOLEAN NOT NULL DEFAULT FALSE,
  disabled         BOOLEAN NOT NULL DEFAULT FALSE,
  created          BIGINT NOT NULL DEFAULT 0,
  updated          BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_rate_item ON trade_rate(item_id, star);

CREATE TABLE IF NOT EXISTS trade_rate_reply (
  reply_id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  rate_id          BIGINT NOT NULL,
  role             VARCHAR(16),                             -- seller/buyer
  content          CLOB,
  created          BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS orders_invoice (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  invoice_apply_bn VARCHAR(64) NOT NULL,                    -- 发票申请单号(生成)
  user_id          BIGINT NOT NULL,
  company_id       BIGINT NOT NULL,
  order_id         VARCHAR(255),                            -- 逗号分隔多订单
  invoice_type     VARCHAR(20),                             -- enterprise/individual
  invoice_type_code VARCHAR(8),                             -- 01专票 02普票
  company_title    VARCHAR(128),
  tax_number       VARCHAR(64),
  email            VARCHAR(128),
  mobile           VARCHAR(32),
  invoice_status   VARCHAR(32) DEFAULT 'pending',
  invoice_amount   BIGINT NOT NULL DEFAULT 0,
  invoice_file_url VARCHAR(512),
  create_time      BIGINT NOT NULL DEFAULT 0,
  update_time      BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_invoice_user ON orders_invoice(user_id);

-- =====================================================================
-- 5. 支付域（PaymentBundle 落库表）
-- =====================================================================

CREATE TABLE IF NOT EXISTS trade (
  trade_id         VARCHAR(64) PRIMARY KEY,                 -- 支付单号(生成)
  order_id         VARCHAR(64) NOT NULL,
  company_id       BIGINT NOT NULL,
  user_id          VARCHAR(64) NOT NULL,
  shop_id          BIGINT NOT NULL DEFAULT 0,
  distributor_id   BIGINT NOT NULL DEFAULT 0,
  total_fee        BIGINT NOT NULL DEFAULT 0,               -- 应付(分)
  discount_fee     BIGINT NOT NULL DEFAULT 0,
  pay_fee          BIGINT NOT NULL DEFAULT 0,               -- 实付(分)
  refunded_fee     BIGINT NOT NULL DEFAULT 0,
  trade_state      VARCHAR(32) NOT NULL DEFAULT 'NOTPAY',   -- NOTPAY/SUCCESS/REFUND/CLOSED…
  pay_type         VARCHAR(32),                             -- wxpay/alipay/point/deposit…
  pay_channel      VARCHAR(32),
  transaction_id   VARCHAR(64),                             -- 渠道单号
  mch_id           VARCHAR(64),
  wxa_appid        VARCHAR(64),
  authorizer_appid VARCHAR(64),
  bank_type        VARCHAR(32),
  body             VARCHAR(255),
  detail           CLOB,
  time_start       VARCHAR(32),
  time_expire      VARCHAR(32),
  cur_fee_type     VARCHAR(5),
  cur_fee_rate     DOUBLE PRECISION,
  cur_pay_fee      BIGINT NOT NULL DEFAULT 0,
  inital_request   CLOB,
  inital_response  CLOB,
  is_settled       BOOLEAN NOT NULL DEFAULT FALSE,
  created          BIGINT NOT NULL DEFAULT 0,
  updated          BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_trade_order ON trade(order_id);
CREATE INDEX IF NOT EXISTS idx_trade_state ON trade(company_id, trade_state);

CREATE TABLE IF NOT EXISTS offline_payment (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_id         BIGINT NOT NULL,
  company_id       BIGINT NOT NULL,
  total_fee        BIGINT NOT NULL DEFAULT 0,
  pay_fee          BIGINT NOT NULL DEFAULT 0,
  check_status     SMALLINT NOT NULL DEFAULT 0,             -- 0待处理1已审核2已拒绝
  pay_sn           VARCHAR(64),
  voucher_pic      CLOB,
  transfer_remark  VARCHAR(512),
  operator_name    VARCHAR(64),
  remark           VARCHAR(512),
  created          BIGINT NOT NULL DEFAULT 0,
  updated          BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_offpay_order ON offline_payment(order_id);

-- =====================================================================
-- 6. 售后域（AftersalesBundle）
-- =====================================================================

CREATE TABLE IF NOT EXISTS aftersales (
  aftersales_bn    BIGINT PRIMARY KEY,                      -- 售后单号(生成)
  detail_id        BIGINT NOT NULL DEFAULT 0,
  order_id         BIGINT NOT NULL,
  company_id       BIGINT NOT NULL,
  user_id          BIGINT NOT NULL,
  shop_id          BIGINT NOT NULL DEFAULT 0,
  distributor_id   BIGINT NOT NULL DEFAULT 0,
  supplier_id      BIGINT NOT NULL DEFAULT 0,
  merchant_id      BIGINT NOT NULL DEFAULT 0,
  item_id          BIGINT NOT NULL,
  item_bn          VARCHAR(255),
  item_name        VARCHAR(255),
  num              BIGINT NOT NULL DEFAULT 1,
  aftersales_type  VARCHAR(32) NOT NULL,                    -- refund_only_return_money/return_money_and_goods…
  aftersales_status INT NOT NULL DEFAULT 0,
  progress         INT NOT NULL DEFAULT 0,
  refund_fee       BIGINT NOT NULL DEFAULT 0,               -- 申请退款(分)
  refund_point     BIGINT NOT NULL DEFAULT 0,
  reason           VARCHAR(300),
  description      VARCHAR(300),
  refuse_reason    VARCHAR(300),
  memo             VARCHAR(300),
  evidence_pic     CLOB,
  return_type      VARCHAR(20),                             -- logistics/offline
  contact          VARCHAR(128),
  mobile           VARCHAR(32),
  freight          BIGINT NOT NULL DEFAULT 0,
  created          BIGINT NOT NULL DEFAULT 0,
  updated          BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_after_order ON aftersales(order_id);
CREATE INDEX IF NOT EXISTS idx_after_user ON aftersales(user_id, aftersales_status);

CREATE TABLE IF NOT EXISTS aftersales_detail (
  detail_id        BIGINT AUTO_INCREMENT PRIMARY KEY,
  aftersales_bn    BIGINT NOT NULL,
  order_id         VARCHAR(64) NOT NULL,
  sub_order_id     BIGINT NOT NULL DEFAULT 0,
  goods_id         BIGINT NOT NULL DEFAULT 0,
  item_id          BIGINT NOT NULL,
  item_name        VARCHAR(255),
  pic              VARCHAR(512),
  num              BIGINT NOT NULL DEFAULT 1,
  refunded_num     BIGINT NOT NULL DEFAULT 0,               -- 实际入库
  refund_fee       BIGINT NOT NULL DEFAULT 0,
  refund_point     BIGINT NOT NULL DEFAULT 0,
  aftersales_type  VARCHAR(32),
  aftersales_status INT NOT NULL DEFAULT 0,
  progress         INT NOT NULL DEFAULT 0,
  created          BIGINT NOT NULL DEFAULT 0,
  updated          BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_afterd_bn ON aftersales_detail(aftersales_bn);

CREATE TABLE IF NOT EXISTS aftersales_refund (
  refund_bn        BIGINT PRIMARY KEY,                      -- 退款单号(生成)
  aftersales_bn    BIGINT NOT NULL,
  order_id         VARCHAR(64) NOT NULL,
  trade_id         VARCHAR(64) NOT NULL,
  item_id          BIGINT NOT NULL DEFAULT 0,
  company_id       BIGINT NOT NULL,
  refund_type      VARCHAR(32),
  refund_channel   VARCHAR(32),
  refund_status    VARCHAR(32) NOT NULL DEFAULT 'READY',    -- READY/AUDIT_SUCC/SUCCESS/FAILED
  order_fee        BIGINT NOT NULL DEFAULT 0,
  refund_fee       BIGINT NOT NULL DEFAULT 0,               -- 申请(分)
  refunded_fee     BIGINT NOT NULL DEFAULT 0,               -- 已退(分)
  refund_point     BIGINT NOT NULL DEFAULT 0,
  refunded_point   BIGINT NOT NULL DEFAULT 0,
  return_freight   BIGINT NOT NULL DEFAULT 0,
  pay_type         VARCHAR(32),
  refund_id        VARCHAR(50),                             -- 渠道退款号
  refund_time      BIGINT,
  refund_success_time BIGINT,
  created          BIGINT NOT NULL DEFAULT 0,
  updated          BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_refund_order ON aftersales_refund(order_id);

-- =====================================================================
-- 7. 营销促销域（PromotionsBundle / KaquanBundle 简化建模）
-- =====================================================================

CREATE TABLE IF NOT EXISTS promotions_activity (
  activity_id      BIGINT AUTO_INCREMENT PRIMARY KEY,
  company_id       BIGINT NOT NULL,
  activity_type    VARCHAR(32) NOT NULL,
  title            VARCHAR(128) NOT NULL,
  trigger_condition CLOB,                                   -- JSON
  discount_config  CLOB,                                    -- JSON
  activity_status  VARCHAR(32) NOT NULL DEFAULT 'draft',
  begin_time       BIGINT NOT NULL DEFAULT 0,
  end_time         BIGINT NOT NULL DEFAULT 0,
  created          BIGINT NOT NULL DEFAULT 0,
  updated          BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS promotions_marketing_activity (
  marketing_id     BIGINT AUTO_INCREMENT PRIMARY KEY,
  company_id       BIGINT NOT NULL,
  marketing_type   VARCHAR(32) NOT NULL,                    -- full_discount/full_minus/addprice_purchase
  marketing_name   VARCHAR(128) NOT NULL,
  ad_pic           VARCHAR(512),
  start_time       BIGINT NOT NULL DEFAULT 0,
  end_time         BIGINT NOT NULL DEFAULT 0,
  release_time     BIGINT,
  use_bound        INT NOT NULL DEFAULT 0,                  -- 0全场1商品2分类
  use_shop         INT NOT NULL DEFAULT 0,
  shop_ids         CLOB,
  condition_type   VARCHAR(32),                             -- quantity/totalfee
  condition_value  CLOB,
  canjoin_repeat   BOOLEAN NOT NULL DEFAULT FALSE,          -- 上不封顶
  join_limit       INT NOT NULL DEFAULT 0,
  free_postage     BOOLEAN NOT NULL DEFAULT FALSE,
  promotion_tag    VARCHAR(15),
  check_status     VARCHAR(32) DEFAULT 'non-reviewed',
  status           BOOLEAN NOT NULL DEFAULT TRUE,
  created          BIGINT NOT NULL DEFAULT 0,
  updated          BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS promotions_marketing_activity_items (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  marketing_id     BIGINT NOT NULL,
  item_id          BIGINT NOT NULL,
  goods_id         BIGINT NOT NULL DEFAULT 0,
  marketing_type   VARCHAR(32),
  item_name        VARCHAR(150),
  price            BIGINT NOT NULL DEFAULT 0,
  promotion_tag    VARCHAR(16),
  start_time       BIGINT,
  end_time         BIGINT,
  status           BOOLEAN NOT NULL DEFAULT TRUE,
  is_show          BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE INDEX IF NOT EXISTS idx_mktitems_mkt ON promotions_marketing_activity_items(marketing_id);

CREATE TABLE IF NOT EXISTS promotions_coupon (
  coupon_id        BIGINT AUTO_INCREMENT PRIMARY KEY,       -- Kotlin 侧统一卡券表(对应 KaquanBundle 卡券)
  company_id       BIGINT NOT NULL,
  coupon_type      VARCHAR(16) NOT NULL,                    -- discount/cash/exchange
  coupon_name      VARCHAR(128) NOT NULL,
  discount_fee     BIGINT NOT NULL DEFAULT 0,               -- 面额/抵扣(分)
  min_order_fee    BIGINT NOT NULL DEFAULT 0,               -- 使用门槛(分)
  total_num        BIGINT NOT NULL DEFAULT 0,
  send_num         BIGINT NOT NULL DEFAULT 0,
  valid_type       VARCHAR(16) DEFAULT 'range',             -- range固定日期/days领后N天
  begin_time       BIGINT,
  end_time         BIGINT,
  valid_days       INT,
  status           BOOLEAN NOT NULL DEFAULT TRUE,
  created          BIGINT NOT NULL DEFAULT 0,
  updated          BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS promotions_coupon_user (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  coupon_id        BIGINT NOT NULL,
  user_id          BIGINT NOT NULL,
  company_id       BIGINT NOT NULL,
  coupon_code      VARCHAR(64),
  status           VARCHAR(16) NOT NULL DEFAULT 'UNUSED',   -- UNUSED/USED/EXPIRED
  order_id         BIGINT NOT NULL DEFAULT 0,
  receive_time     BIGINT,
  use_time         BIGINT,
  expire_time      BIGINT
);
CREATE INDEX IF NOT EXISTS idx_couponuser ON promotions_coupon_user(user_id, status);

CREATE TABLE IF NOT EXISTS promotions_seckill_activity (
  seckill_id       BIGINT AUTO_INCREMENT PRIMARY KEY,
  company_id       BIGINT NOT NULL,
  activity_name    VARCHAR(128),
  ad_pic           VARCHAR(512),
  activity_start_time BIGINT NOT NULL,
  activity_end_time   BIGINT NOT NULL,
  release_time     BIGINT,
  distributor_id   VARCHAR(255),                            -- 逗号分隔多店
  seckill_type     VARCHAR(16) DEFAULT 'normal',
  limit_money      BIGINT NOT NULL DEFAULT 0,
  validity_period  INT NOT NULL DEFAULT 0,                  -- 下单保留分钟
  disabled         BOOLEAN NOT NULL DEFAULT FALSE,
  created          BIGINT NOT NULL DEFAULT 0,
  updated          BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS promotions_seckill_rel_goods (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  seckill_id       BIGINT NOT NULL,
  item_id          BIGINT NOT NULL,
  item_title       VARCHAR(255),
  pic              VARCHAR(512),
  activity_price   BIGINT NOT NULL DEFAULT 0,               -- 分
  activity_store   BIGINT NOT NULL DEFAULT 0,
  sales_store      BIGINT NOT NULL DEFAULT 0,
  limit_num        INT NOT NULL DEFAULT 0,
  is_show          BOOLEAN NOT NULL DEFAULT TRUE,
  disabled         BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX IF NOT EXISTS idx_seckgoods ON promotions_seckill_rel_goods(seckill_id);

CREATE TABLE IF NOT EXISTS promotion_groups_activity (
  groups_activity_id BIGINT AUTO_INCREMENT PRIMARY KEY,
  company_id       BIGINT NOT NULL,
  act_name         VARCHAR(128),
  goods_id         BIGINT NOT NULL,
  act_price        BIGINT NOT NULL DEFAULT 0,               -- 拼团价(分)
  person_num       INT NOT NULL DEFAULT 2,                  -- 成团人数
  store            BIGINT NOT NULL DEFAULT 0,
  limit_buy_num    INT NOT NULL DEFAULT 0,
  limit_time       INT NOT NULL DEFAULT 24,                 -- 成团时效(小时)
  begin_time       BIGINT NOT NULL,
  end_time         BIGINT NOT NULL,
  disabled         BOOLEAN NOT NULL DEFAULT FALSE,
  created          BIGINT NOT NULL DEFAULT 0,
  updated          BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS promotion_groups_team (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  team_id          VARCHAR(100) NOT NULL,                   -- 团号(生成)
  act_id           BIGINT NOT NULL,
  head_user_id     BIGINT NOT NULL,                         -- 团长
  join_person_num  INT NOT NULL DEFAULT 1,
  team_status      INT NOT NULL DEFAULT 1,                  -- 1进行中2成功3失败
  begin_time       BIGINT NOT NULL,
  end_time         BIGINT,
  created          BIGINT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_team ON promotion_groups_team(team_id);

CREATE TABLE IF NOT EXISTS promotion_groups_team_member (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  team_id          VARCHAR(100) NOT NULL,
  act_id           BIGINT NOT NULL,
  user_id          BIGINT NOT NULL,                         -- 0=机器人
  order_id         VARCHAR(100),
  join_time        BIGINT NOT NULL
);

-- =====================================================================
-- 8. 店铺 / 分销域（DistributionBundle）
-- =====================================================================

CREATE TABLE IF NOT EXISTS distribution_distributor (
  distributor_id   BIGINT AUTO_INCREMENT PRIMARY KEY,
  company_id       BIGINT NOT NULL,
  is_distributor   BOOLEAN NOT NULL DEFAULT FALSE,          -- 是否商城主店铺
  name             VARCHAR(128),
  mobile           VARCHAR(32),
  province         VARCHAR(64),
  city             VARCHAR(64),
  area             VARCHAR(64),
  address          VARCHAR(255),
  lng              VARCHAR(32),
  lat              VARCHAR(32),
  logo             VARCHAR(512),
  banner           VARCHAR(512),
  introduce        CLOB,
  is_valid         VARCHAR(16) NOT NULL DEFAULT 'true',     -- true/false/closed
  review_status    BOOLEAN NOT NULL DEFAULT FALSE,
  source_from      INT NOT NULL DEFAULT 1,                  -- 1管理端 2小程序申请 3开放接口
  shop_id          BIGINT NOT NULL DEFAULT 0,
  distribution_type SMALLINT NOT NULL DEFAULT 0,            -- 0自营 1加盟
  auto_sync_goods  BOOLEAN NOT NULL DEFAULT FALSE,
  is_ziti          BOOLEAN NOT NULL DEFAULT FALSE,          -- 支持自提
  is_delivery      BOOLEAN NOT NULL DEFAULT FALSE,          -- 支持物流
  is_self_delivery BOOLEAN NOT NULL DEFAULT FALSE,          -- 支持自配送
  payment_subject  SMALLINT NOT NULL DEFAULT 0,             -- 0平台收款 1店铺收款
  rate             INT NOT NULL DEFAULT 0,                  -- 平台服务费率(万分比)
  created          BIGINT NOT NULL DEFAULT 0,
  updated          BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_dist_company ON distribution_distributor(company_id, is_valid);

CREATE TABLE IF NOT EXISTS distribution_distributor_items (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  distributor_id   BIGINT NOT NULL,
  item_id          BIGINT NOT NULL,
  default_item_id  BIGINT NOT NULL DEFAULT 0,
  goods_id         BIGINT NOT NULL DEFAULT 0,
  store            BIGINT NOT NULL DEFAULT 0,               -- 店铺库存
  price            BIGINT NOT NULL DEFAULT 0,               -- 店铺价(分)
  is_show          BOOLEAN NOT NULL DEFAULT TRUE,
  is_can_sale      BOOLEAN NOT NULL DEFAULT TRUE,
  is_total_store   BOOLEAN NOT NULL DEFAULT TRUE,           -- 使用总部库存
  sales            BIGINT NOT NULL DEFAULT 0,
  shop_id          BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_distitems ON distribution_distributor_items(distributor_id, item_id);

CREATE TABLE IF NOT EXISTS distribution_distributor_salesman (
  salesman_id      BIGINT AUTO_INCREMENT PRIMARY KEY,
  company_id       BIGINT NOT NULL,
  distributor_id   BIGINT NOT NULL,
  shop_id          BIGINT NOT NULL DEFAULT 0,
  user_id          BIGINT NOT NULL DEFAULT 0,
  mobile           VARCHAR(32),
  salesman_name    VARCHAR(32),
  role_id          BIGINT NOT NULL DEFAULT 0,
  is_valid         VARCHAR(16) NOT NULL DEFAULT 'true',
  created          BIGINT NOT NULL DEFAULT 0,
  updated          BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS distribution_distribute_logs (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  company_id       BIGINT NOT NULL,
  distributor_id   BIGINT NOT NULL,
  order_id         BIGINT NOT NULL,
  item_id          BIGINT NOT NULL,
  user_id          BIGINT NOT NULL,
  shop_id          BIGINT NOT NULL DEFAULT 0,
  num              BIGINT NOT NULL DEFAULT 1,
  rebate           BIGINT NOT NULL DEFAULT 0,               -- 分佣(分)
  total_rebate     BIGINT NOT NULL DEFAULT 0,
  is_close         BOOLEAN NOT NULL DEFAULT FALSE,          -- 已结算
  plan_close_time  BIGINT,
  created          BIGINT NOT NULL DEFAULT 0,
  updated          BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_distlog_order ON distribution_distribute_logs(order_id);

CREATE TABLE IF NOT EXISTS distribution_cash_withdrawal (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  company_id       BIGINT NOT NULL,
  distributor_id   BIGINT NOT NULL,
  user_id          BIGINT NOT NULL,
  shop_id          BIGINT NOT NULL DEFAULT 0,
  money            BIGINT NOT NULL DEFAULT 0,               -- 提现金额(分)
  status           VARCHAR(16) NOT NULL DEFAULT 'applied',  -- applied/confirmed/paid/refused
  remarks          VARCHAR(255),
  created          BIGINT NOT NULL DEFAULT 0,
  updated          BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS distribution_pickup_location (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  company_id       BIGINT NOT NULL,
  distributor_id   BIGINT NOT NULL,
  rel_distributor_id BIGINT NOT NULL DEFAULT 0,
  name             VARCHAR(128),
  province         VARCHAR(64),
  city             VARCHAR(64),
  area             VARCHAR(64),
  address          VARCHAR(255),
  lng              VARCHAR(32),
  lat              VARCHAR(32),
  contract_phone   VARCHAR(20),
  status           SMALLINT NOT NULL DEFAULT 1,
  created          BIGINT NOT NULL DEFAULT 0,
  updated          BIGINT NOT NULL DEFAULT 0
);

-- =====================================================================
-- 9. 结算域（Statements，OrdersBundle 内）
-- =====================================================================

CREATE TABLE IF NOT EXISTS statements (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  statement_no     VARCHAR(20) NOT NULL,
  company_id       BIGINT NOT NULL,
  merchant_id      BIGINT NOT NULL DEFAULT 0,
  supplier_id      BIGINT NOT NULL DEFAULT 0,
  distributor_id   BIGINT NOT NULL DEFAULT 0,
  merchant_type    VARCHAR(32),                             -- distributor/supplier
  order_num        INT NOT NULL DEFAULT 0,
  total_fee        BIGINT NOT NULL DEFAULT 0,
  freight_fee      BIGINT NOT NULL DEFAULT 0,
  rebate_fee       BIGINT NOT NULL DEFAULT 0,
  refund_fee       BIGINT NOT NULL DEFAULT 0,
  statement_fee    BIGINT NOT NULL DEFAULT 0,               -- 应结(分)
  commission_fee   BIGINT NOT NULL DEFAULT 0,
  start_time       BIGINT NOT NULL,
  end_time         BIGINT NOT NULL,
  statement_status VARCHAR(16) NOT NULL DEFAULT 'ready',    -- ready/confirmed/paid
  created          BIGINT NOT NULL DEFAULT 0,
  updated          BIGINT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_statement_no ON statements(statement_no);

CREATE TABLE IF NOT EXISTS statement_details (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  statement_id     BIGINT NOT NULL,
  order_id         BIGINT NOT NULL,
  num              INT NOT NULL DEFAULT 1,
  total_fee        BIGINT NOT NULL DEFAULT 0,
  rebate_fee       BIGINT NOT NULL DEFAULT 0,
  refund_fee       BIGINT NOT NULL DEFAULT 0,
  refund_num       INT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_stadetail ON statement_details(statement_id);

-- =====================================================================
-- 10. 系统支撑（验证码/导出/上传记录/系统配置）
-- =====================================================================

CREATE TABLE IF NOT EXISTS sys_config (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  company_id       BIGINT NOT NULL DEFAULT 0,               -- 0=全局
  config_key       VARCHAR(128) NOT NULL,
  config_value     CLOB,
  updated          BIGINT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_cfg_key ON sys_config(company_id, config_key);

CREATE TABLE IF NOT EXISTS upload_files (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  company_id       BIGINT NOT NULL,
  user_id          BIGINT,
  file_name        VARCHAR(255),
  file_path        VARCHAR(512),
  file_type        VARCHAR(32),
  file_size        BIGINT,
  created          BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS seq_generator (
  seq_name         VARCHAR(64) PRIMARY KEY,                 -- 号段名称: order_no/trade_no/refund_no…
  current_value    BIGINT NOT NULL DEFAULT 0,
  version          INT NOT NULL DEFAULT 0                   -- 乐观锁
);

-- 号段种子（INSERT IGNORE 保证脚本可重复执行）
INSERT IGNORE INTO seq_generator (seq_name, current_value) VALUES ('order_no', 0);
INSERT IGNORE INTO seq_generator (seq_name, current_value) VALUES ('trade_no', 0);
INSERT IGNORE INTO seq_generator (seq_name, current_value) VALUES ('refund_no', 0);
INSERT IGNORE INTO seq_generator (seq_name, current_value) VALUES ('aftersales_no', 0);

-- ===== M0-7 支付域补列（追加于末尾，不回改上文）=====
-- 订单表落一列实付：结清时由 PayService 写入，未付款恒 0。
-- 渠道侧金额仍以 trade.pay_fee 为准，这里只是列表/详情的读视图，避免每次都对 trade 做 join。
ALTER TABLE orders_normal_orders ADD COLUMN IF NOT EXISTS pay_fee BIGINT NOT NULL DEFAULT 0;

-- ===== M1-1 售后域补列（追加于末尾，不回改上文）=====
-- 回寄物流与超时自动驳回倒计时：源系统 sendback_data 是 JSON 串（corp_code/logi_no/填报人/时间），
-- auto_refuse_time 是"商家审核通过后要求买家寄回的截止秒"，0=不限时（沿用 PHP 语义）。
ALTER TABLE aftersales ADD COLUMN IF NOT EXISTS sendback_data CLOB;
ALTER TABLE aftersales ADD COLUMN IF NOT EXISTS auto_refuse_time BIGINT NOT NULL DEFAULT 0;
-- 主表冗余商品图：§11.3 行字段含 pic，列表页只查主表（明细表有 pic，但列表按行取要 join 会退化成一串子查询）
ALTER TABLE aftersales ADD COLUMN IF NOT EXISTS pic VARCHAR(512);

-- 线下打款登记（AF-A10）：银行七字段落退款单本身，源系统同表同列语义
ALTER TABLE aftersales_refund ADD COLUMN IF NOT EXISTS payee_name VARCHAR(128);
ALTER TABLE aftersales_refund ADD COLUMN IF NOT EXISTS bank_name VARCHAR(128);
ALTER TABLE aftersales_refund ADD COLUMN IF NOT EXISTS bank_account VARCHAR(64);
ALTER TABLE aftersales_refund ADD COLUMN IF NOT EXISTS bank_branch VARCHAR(128);
ALTER TABLE aftersales_refund ADD COLUMN IF NOT EXISTS bank_province VARCHAR(64);
ALTER TABLE aftersales_refund ADD COLUMN IF NOT EXISTS bank_city VARCHAR(64);
ALTER TABLE aftersales_refund ADD COLUMN IF NOT EXISTS memo VARCHAR(300);

-- ===== M1-2 真实渠道：渠道侧关单确认位（追加于末尾，不回改上文）=====
-- 本地把支付单收掉（trade_state='CLOSED'）只是我方状态：微信/支付宝那边那张二维码在超时窗口内还能付款。
-- 补单作业对已关闭但没向渠道发过关单的支付单逐轮发 close，成功或渠道答"已支付/不存在"都置 1 停止重试
-- （后两者都无需再关：已支付的钱由回调/补单走结清，不存在的单本来就关不掉）。
ALTER TABLE trade ADD COLUMN IF NOT EXISTS channel_closed TINYINT NOT NULL DEFAULT 0;

-- ===== M1-3 营销域（券/满减满折/秒杀/拼团）补列（追加于末尾，不回改上文）=====

-- 券模板：每人限领张数（0=不限）。源系统的 get_limit 在 kaquan_discount_cards，本表原先没带。
ALTER TABLE promotions_coupon ADD COLUMN IF NOT EXISTS get_limit INT NOT NULL DEFAULT 0;
ALTER TABLE promotions_coupon ADD COLUMN IF NOT EXISTS instruction VARCHAR(255);

-- 券实例快照：领取那一刻把面额/门槛/名称冻结进来。
-- 后台改券（CP-A4）不回溯已发出的券——否则"满100减20"改成"满100减5"会把
-- 用户手里已经领到的券当面作废，历史单对账也讲不清当时抵了多少。源系统 kaquan_user_discount 同样冗余四列。
ALTER TABLE promotions_coupon_user ADD COLUMN IF NOT EXISTS coupon_name VARCHAR(128);
ALTER TABLE promotions_coupon_user ADD COLUMN IF NOT EXISTS coupon_type VARCHAR(16);
ALTER TABLE promotions_coupon_user ADD COLUMN IF NOT EXISTS discount_fee BIGINT NOT NULL DEFAULT 0;
ALTER TABLE promotions_coupon_user ADD COLUMN IF NOT EXISTS min_order_fee BIGINT NOT NULL DEFAULT 0;
ALTER TABLE promotions_coupon_user ADD COLUMN IF NOT EXISTS lock_time BIGINT NOT NULL DEFAULT 0;
-- receive=自领 / send=后台发券（CP-A8），退券与统计要分得开这两种来源
ALTER TABLE promotions_coupon_user ADD COLUMN IF NOT EXISTS source VARCHAR(16) NOT NULL DEFAULT 'receive';
-- 券码是下单入参（coupon_code），必须全局唯一才能定位到"哪一张券"
CREATE UNIQUE INDEX IF NOT EXISTS uk_coupon_code ON promotions_coupon_user(coupon_code);

-- 秒杀：Redis 库存池改 DB 三列（D24）。available = activity_store - sales_store - hold_store，
-- hold_store 是"下单未付占用"，结清时 hold→sales，取消/超时释放 hold，三条都是条件更新。
ALTER TABLE promotions_seckill_rel_goods ADD COLUMN IF NOT EXISTS hold_store BIGINT NOT NULL DEFAULT 0;
-- 一场次一商品只一行：补唯一键，配置台重复提交同一 SKU 是覆盖不是插第二行
CREATE UNIQUE INDEX IF NOT EXISTS uk_seckill_goods ON promotions_seckill_rel_goods(seckill_id, item_id);

-- 拼团：虚拟成团开关（源系统 robot 列）。成团失败自动退是主路径，机器人凑团只是运营兜底选项。
ALTER TABLE promotion_groups_activity ADD COLUMN IF NOT EXISTS robot BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE promotion_groups_activity ADD COLUMN IF NOT EXISTS instruction VARCHAR(255);
-- 成员行的"已付"位：下单即写行（未付不占成团人数），结清才置真 —— 
-- 成团判定只数已付成员，与 PHP 用 disabled 列表达同一件事，取布尔直白命名。
ALTER TABLE promotion_groups_team_member ADD COLUMN IF NOT EXISTS paid BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE promotion_groups_team_member ADD COLUMN IF NOT EXISTS user_nick VARCHAR(128);
ALTER TABLE promotion_groups_team ADD COLUMN IF NOT EXISTS robot_num INT NOT NULL DEFAULT 0;
-- 一人一团一名额（与源系统"同一会员同一活动只允许一笔待成团单"同口径）：
-- 唯一键把"同一用户并发参两次团"钉死在数据库层，比服务层先查后插可靠。
-- 机器人行因此不能共用一个 user_id=0（第二行就撞键），改为负数递编号（-1、-2…），
-- 出参仍按源系统口径报"虚拟成员"。
CREATE UNIQUE INDEX IF NOT EXISTS uk_team_member ON promotion_groups_team_member(team_id, user_id);

-- 拼团单挂团号：PHP 写在 orders 扩展表里，本系统主表直接落一列（订单详情/参团列表都要读它）。
ALTER TABLE orders_normal_orders ADD COLUMN IF NOT EXISTS team_id VARCHAR(100);

-- 优惠快照表启用（M0 建了表没人写）：每笔优惠落一行，退款/对账按它复现"当时抵了多少"。
-- 券抵扣落两列（activity_id 只到"哪个活动"，说不清是哪张券），满减/秒杀沿用既有列。
ALTER TABLE orders_rel_promotions ADD COLUMN IF NOT EXISTS coupon_id BIGINT NOT NULL DEFAULT 0;
ALTER TABLE orders_rel_promotions ADD COLUMN IF NOT EXISTS coupon_code VARCHAR(64);
CREATE INDEX IF NOT EXISTS idx_relpro_coupon ON orders_rel_promotions(coupon_id);

-- 团号与券码发号器（D29：team_id = "GT" + 号段值，唯一索引 uk_team 兜底；券码同构）
INSERT IGNORE INTO seq_generator (seq_name, current_value) VALUES ('group_team_id', 0);
INSERT IGNORE INTO seq_generator (seq_name, current_value) VALUES ('coupon_code', 0);

-- 秒杀类型列放宽：本项目用**列值**表达类型（`normal` / `limited_time_sale`），
-- 源系统的整数码 1/2 由服务层双写映射。`limited_time_sale` 17 个字符，建表时的 VARCHAR(16) 装不下。
ALTER TABLE promotions_seckill_activity ALTER COLUMN seckill_type SET DATA TYPE VARCHAR(32);

-- ===== M1-4 追加：店铺商品唯一键（追加于末尾，不回改上文） =====
-- SI-A1 是"按 (店, 商品) upsert"，服务层先查后插；并发下两条同名挂品会各自成立，
-- 于是同店同品出现两行、扣库存只命中其中一行 —— 唯一键把它钉在数据库层（与 uk_team 同思路）。
CREATE UNIQUE INDEX IF NOT EXISTS uk_distitems ON distribution_distributor_items(distributor_id, item_id);

-- ===== M1-4 第三批追加：自提单挂点 + 取货码号段（追加于末尾，不回改上文） =====
-- 自提单除了三列（receipt_type/ziti_code/ziti_status）还得知道"去哪儿取"。
-- 点地址不落快照而是存 id：点改了门牌号，核销页要显示现在能取到货的地址，不是两年前那条。
-- 历史单的取货地址靠"停用不删行"（PL-A4）保住，而不是靠订单里的副本。
ALTER TABLE orders_normal_orders ADD COLUMN IF NOT EXISTS pickup_location_id BIGINT NOT NULL DEFAULT 0;
-- PL-A4 停用前的"还有几张未核销的单指着它"是这张表上唯一的按点反查，建索引而不是扫全单表。
CREATE INDEX IF NOT EXISTS idx_ziti_pickup ON orders_normal_orders(pickup_location_id, receipt_type, ziti_status);
-- 取货码号段（§18.7）：SeqGenerator 取段时 check(touched==1)，行不存在就直接抛，必须在 schema 里先建好。
INSERT IGNORE INTO seq_generator (seq_name, current_value) VALUES ('ziti_code', 0);

-- ===== M1-4 第三批后半（3B）追加：导购 SM + 员工端第三条链（§18.3/§18.7）（追加于末尾，不回改上文） =====
-- 员工端要能登录：口令列与 `operators.password` 同宽同算法（bcrypt cost=10）。
-- 没设过密码的导购（NULL）登录一律 401 —— 空串不能当"没密码"，否则一次误更新就把账号开放出去。
ALTER TABLE distribution_distributor_salesman ADD COLUMN IF NOT EXISTS password VARCHAR(255);
-- SM-F1「扫码进店」要显示导购头像，源系统在 `salesman.avatar`；可空，前端拿不到就占位。
ALTER TABLE distribution_distributor_salesman ADD COLUMN IF NOT EXISTS avatar VARCHAR(255);
-- 「同一店内手机号唯一」是 SM-A2 那条 422 的落点：服务层先查后判管形状，这条唯一键管并发那半边。
-- `mobile` 可空，而 NULL 在唯一索引里互不相等，历史脏行不会被这条键卡住建表。
CREATE UNIQUE INDEX IF NOT EXISTS uk_salesman_mobile ON distribution_distributor_salesman(distributor_id, mobile);

-- 导购角色（SM-A6）。两个形状沿用本批自提点的既有约定，不另立一套：
--   `distributor_id=0` = 平台通用角色（每家店都能选），与「平台通用自提点」同一套语义；
--   `status` 1 启用 / 0 停用（也是逻辑停用、行留着给历史导购指认岗位名），SM-A4 调岗只认启用中的角色。
CREATE TABLE IF NOT EXISTS distribution_distributor_salesman_role (
  role_id        BIGINT AUTO_INCREMENT PRIMARY KEY,
  company_id     BIGINT NOT NULL,
  distributor_id BIGINT NOT NULL DEFAULT 0,
  role_name      VARCHAR(64),
  remark         VARCHAR(255),
  status         INT NOT NULL DEFAULT 1,
  created        BIGINT NOT NULL DEFAULT 0,
  updated        BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_smanrole ON distribution_distributor_salesman_role(company_id, distributor_id, status);

-- 佣金流水挂导购：ST-E4/E5「我的统计/我的佣金」与 CW-F3 的可提余额都按这一列聚合。
-- 入账时从订单头 `salesman_id` 带过来（代客下单由会话强制注入，会员自下单由 `members.salesman_id` 回落），
-- 不在结算时按"现在这个会员归谁"回读 —— 与 D46 同一族理由：当时那条行才是事实。
ALTER TABLE distribution_distribute_logs ADD COLUMN IF NOT EXISTS salesman_id BIGINT NOT NULL DEFAULT 0;
CREATE INDEX IF NOT EXISTS idx_distlog_salesman ON distribution_distribute_logs(salesman_id, is_close);
-- 提现单挂导购（CW-F3/F4）：店铺提现认 `distributor_id + user_id`，导购提现认 `salesman_id`，
-- 两套余额各自算各自的，不合并成一列 —— 合并之后"老板把自己的店提空了还是不是导购的余额"说不清。
ALTER TABLE distribution_cash_withdrawal ADD COLUMN IF NOT EXISTS salesman_id BIGINT NOT NULL DEFAULT 0;
CREATE INDEX IF NOT EXISTS idx_withdraw_salesman ON distribution_cash_withdrawal(salesman_id);
-- 会员当前归属导购（SM-F2）。源系统另有带「24h 有效绑定」的关系表，本项目只留当前归属、可换绑（D62）。
ALTER TABLE members ADD COLUMN IF NOT EXISTS salesman_id BIGINT NOT NULL DEFAULT 0;

-- ===== M1-5 追加：评价 / 发票 / 发号的数据形状（docs/04 §23.4，追加于末尾，不回改上文） =====
-- 评价要能按店铺收窄：加盟体系里「这条差评是谁家的」是运营的第一筛，而 `trade_rate` 原本只有
-- `goods_id/item_id`，归属只能靠再 join 一次订单。归属在落单那刻定死（C177 同一条纪律），
-- 落评价时从订单行带下来，不在读时回查「这商品现在挂在哪家」。
ALTER TABLE trade_rate ADD COLUMN IF NOT EXISTS distributor_id BIGINT NOT NULL DEFAULT 0;
ALTER TABLE trade_rate ADD COLUMN IF NOT EXISTS shop_id BIGINT NOT NULL DEFAULT 0;
-- 匿名与会员改名之后，仍要能看到「当时那个名字」：出参位固定存在，不靠 join members 现读。
ALTER TABLE trade_rate ADD COLUMN IF NOT EXISTS nickname VARCHAR(64);
-- 注意 `idx_rate_item(item_id, star)` 是建表时那条，这里必须另起名 ——
-- 同名索引的 `IF NOT EXISTS` 只会静默跳过，不会帮你把索引形状改掉。
CREATE INDEX IF NOT EXISTS idx_rate_company_item ON trade_rate(company_id, item_id, created);
CREATE INDEX IF NOT EXISTS idx_rate_order ON trade_rate(company_id, order_id);

-- 后台处理发票的备注，`INV-A2` 那条「rejected 必须带 remark」的判据读这一列。
ALTER TABLE orders_invoice ADD COLUMN IF NOT EXISTS remark VARCHAR(255);
-- 一单一申请（INV-R1）：源系统 `order_id` 允许逗号分隔多订单，本项目收紧成单个订单号 ——
-- `invoice_amount` 只能对着一个订单算，多单合开的金额归属说不清。P2 放开时去掉这条 uk 即可。
-- `orders_invoice` 建表起就没写过一行数据（全仓零引用），加这条唯一键不会卡住任何历史行。
CREATE UNIQUE INDEX IF NOT EXISTS uk_invoice_order ON orders_invoice(company_id, order_id);
CREATE INDEX IF NOT EXISTS idx_invoice_status ON orders_invoice(company_id, invoice_status);

-- 发票申请单号号段：缺行时 SeqGenerator 的 `check(touched==1)` 直接抛 → 500 而不是 4xx（C166 那一课）。
INSERT IGNORE INTO seq_generator (seq_name, current_value) VALUES ('invoice_apply_bn', 0);
