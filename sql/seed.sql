-- =====================================================================
-- kotlin-ecshopx 初始化数据脚本（H2, 配合 schema.sql 使用）
-- 独立部署模式：company_id 固定为 1（对应 PHP config/common.php system_companys_id）
-- =====================================================================
-- 表在连接默认 schema（见 schema.sql 顶部说明），此处不再 SET SCHEMA

-- 租户
INSERT IGNORE INTO company (company_id, company_name, status, system_is_saas, created, updated)
VALUES (1, 'ECShopX Kotlin 演示租户', 'normal', FALSE, 0, 0);

-- 运营账号：admin / admin123（BCrypt 真实哈希，cost=10，jshell spring-security-crypto 生成）
INSERT IGNORE INTO operators (operator_id, company_id, username, password, operator_type, created, updated)
VALUES (1, 1, 'admin', '$2a$10$gOYr5CPR.WnU/oVv49.JmeEPIk.kywxGMoWzlrbhO1ujEuM3rCyxi', 'admin', 0, 0);

-- 角色与权限资源（示例：商品/订单管理）
INSERT IGNORE INTO roles (role_id, company_id, role_name, role_type) VALUES (1, 1, '超级管理员', 'system');
INSERT IGNORE INTO resources (resource_id, parent_id, resource_name, resource_code, resource_type) VALUES
  (1, 0, '商品管理', 'goods', 'catalog'),
  (2, 1, '商品列表', 'goods.items.lists', 'actived'),
  (3, 1, '商品编辑', 'goods.items.update', 'actived'),
  (4, 0, '订单管理', 'orders', 'catalog'),
  (5, 4, '订单列表', 'orders.lists', 'actived'),
  (6, 4, '订单发货', 'orders.delivery', 'actived');
INSERT IGNORE INTO role_rel_resources (role_id, resource_id) VALUES (1,1),(1,2),(1,3),(1,4),(1,5),(1,6);
INSERT IGNORE INTO operator_rel_roles (operator_id, role_id) VALUES (1, 1);

-- 会员等级
INSERT IGNORE INTO members_grade (grade_id, company_id, grade_name, level, growth_value, discount) VALUES
  (1, 1, '普通会员', 1, 0, 100),
  (2, 1, '银卡会员', 2, 10000, 98),
  (3, 1, '金卡会员', 3, 50000, 95);

-- 演示会员：手机号 13800000001，积分/余额账户
INSERT IGNORE INTO members (user_id, company_id, grade_id, mobile, user_card_code, created, updated)
VALUES (1, 1, 1, '13800000001', '80000001', 0, 0);
INSERT IGNORE INTO members_info (user_id, username, name, sex) VALUES (1, '演示用户', '张三', 1);
INSERT IGNORE INTO point_member (company_id, user_id, point, total_point) VALUES (1, 1, 1000, 1000);
INSERT IGNORE INTO user_deposit (company_id, user_id, deposit_amount) VALUES (1, 1, 0);

-- 店铺（商城主店铺 distributor_id=1 + 一家加盟店）
INSERT IGNORE INTO distribution_distributor
  (distributor_id, company_id, is_distributor, name, mobile, is_valid, source_from, distribution_type, payment_subject, created, updated)
VALUES
  (1, 1, TRUE,  '商城总部', '13900000000', 'true', 1, 0, 0, 0, 0),
  (2, 1, FALSE, 'demo 旗舰店', '13900000001', 'true', 2, 1, 1, 0, 0);

-- 运费模板（全国包邮）
INSERT IGNORE INTO shipping_templates (template_id, company_id, name, is_free, valuation, status, created, updated)
VALUES (1, 1, '默认包邮模板', 'y', '1', TRUE, 0, 0);

-- 商品分类（两级）
INSERT IGNORE INTO items_category (category_id, company_id, parent_id, category_name, category_level, path, is_main_category, sort) VALUES
  (1, 1, 0, '数码电器', 1, '0-1', TRUE, 1),
  (2, 1, 1, '手机通讯', 2, '0-1-2', FALSE, 1),
  (3, 1, 0, '食品生鲜', 1, '0-3', TRUE, 2),
  (4, 1, 3, '休闲零食', 2, '0-3-4', FALSE, 1);

-- 演示商品（SPU+SKU 各 3 条；价格单位=分）
INSERT IGNORE INTO items (item_id, company_id, goods_id, default_item_id, is_default, item_name, item_bn, price, cost_price, market_price, store, sales, approve_status, audit_status, templates_id, item_type, distributor_id, pics, created, updated) VALUES
  (1, 1, 1, 1, TRUE,  '测试手机 X1', 'SKU-X1', 199900, 150000, 219900, 100, 5, 'onsale', 'approved', 1, 'normal', 1, '["/img/x1-1.jpg"]', 0, 0),
  (2, 1, 1, 1, FALSE, '测试手机 X1 白色 128G', 'SKU-X1-W128', 199900, 150000, 219900, 50, 2, 'onsale', 'approved', 1, 'normal', 1, '["/img/x1-w.jpg"]', 0, 0),
  (3, 1, 3, 3, TRUE,  '每日坚果礼盒', 'SKU-NUT-01', 8900, 5000, 9900, 200, 30, 'onsale', 'approved', 1, 'normal', 1, '["/img/nut.jpg"]', 0, 0);
INSERT IGNORE INTO goods_items_rel_cats (item_id, category_id, company_id, created, updated) VALUES
  (1, 2, 1, 0, 0), (2, 2, 1, 0, 0), (3, 4, 1, 0, 0);

-- 卡券（满100减20券 × 100 张）
INSERT IGNORE INTO promotions_coupon (coupon_id, company_id, coupon_type, coupon_name, discount_fee, min_order_fee, total_num, valid_type, begin_time, end_time, status)
VALUES (1, 1, 'discount', '满100减20', 2000, 10000, 100, 'range', 0, 4102444800, TRUE);

-- 全局配置示例
INSERT IGNORE INTO sys_config (company_id, config_key, config_value, updated) VALUES
  (0, 'system.is_saas', 'false', 0),
  (1, 'order.auto_cancel_minutes', '30', 0),
  (1, 'point.deduct.max_ratio', '50', 0);

-- 号段行已在 schema.sql 中初始化；起始值只前进不回退（重启安全）
UPDATE seq_generator SET current_value = GREATEST(current_value, 100000) WHERE seq_name IN ('order_no','trade_no','refund_no','aftersales_no');

-- ===== M0-3 追加：鉴权用例账号 =====
-- viewer / viewer123：staff 类型、未挂任何角色 —— 用于越权(403)用例
INSERT IGNORE INTO operators (operator_id, company_id, username, password, operator_type, created, updated)
VALUES (2, 1, 'viewer', '$2a$10$YEbWtixU4m1Hy41ZGdreMudQjBcFAp1BXcphiR1ZwdwpwIryD2PWC', 'staff', 0, 0);

-- ===== M0-4 追加：商品域规格/条码/标签/服务标签/预警阈值演示数据 =====
-- 属性定义：spec=规格轴（颜色/内存），item_param=参数（产地）
INSERT IGNORE INTO items_attributes (attribute_id, company_id, attribute_type, attribute_name, attribute_show, sort) VALUES
  (1, 1, 'spec', '颜色', 'select', 1),
  (2, 1, 'spec', '内存', 'select', 2),
  (3, 1, 'item_param', '产地', 'text', 3);
INSERT IGNORE INTO items_attribute_values (attribute_value_id, attribute_id, company_id, attribute_value, sort) VALUES
  (1, 1, 1, '白色', 1), (2, 1, 1, '黑色', 2),
  (3, 2, 1, '128G', 1), (4, 2, 1, '256G', 2),
  (5, 3, 1, '中国大陆', 1);

-- 主行 X1 挂产地参数；子行 2（白色 128G）挂两条规格
INSERT IGNORE INTO items_rel_attributes (id, item_id, attribute_id, attribute_type, attribute_value_id, company_id) VALUES
  (1, 1, 3, 'item_param', 5, 1),
  (2, 2, 1, 'spec', 1, 1),
  (3, 2, 2, 'spec', 3, 1);

-- 多规格主行/子行 nospec=FALSE（GOODS-1 主行聚合 spec_items 的前提）
UPDATE items SET nospec = FALSE, warning_store = 10 WHERE item_id = 1;
UPDATE items SET nospec = FALSE, warning_store = 5 WHERE item_id IN (2, 4);
UPDATE items SET warning_store = 250 WHERE item_id = 3;   -- 让 is_warning=true 有命中

-- X1 第二条 SKU（黑色 256G）
INSERT IGNORE INTO items (item_id, company_id, goods_id, default_item_id, is_default, item_name, item_bn, price, cost_price, market_price, store, warning_store, sales, nospec, item_unit, pics, approve_status, audit_status, templates_id, item_type, distributor_id, created, updated) VALUES
  (4, 1, 1, 1, FALSE, '测试手机 X1 黑色 256G', 'X1-BLK-256', 249900, 180000, 269900, 30, 5, 1, TRUE, '台', '["/x1-blk.jpg"]', 'onsale', 'approved', 1, 'normal', 1, 0, 0);
INSERT IGNORE INTO items_rel_attributes (id, item_id, attribute_id, attribute_type, attribute_value_id, company_id) VALUES
  (4, 4, 1, 'spec', 2, 1), (5, 4, 2, 'spec', 4, 1);
INSERT IGNORE INTO goods_items_rel_cats (item_id, category_id, company_id, created, updated) VALUES (4, 2, 1, 0, 0);

-- 条码
INSERT IGNORE INTO items_barcode (id, item_id, barcode, default_item_id, company_id) VALUES
  (1, 2, '6901234500011', 1, 1), (2, 4, '6901234500028', 1, 1), (3, 3, '6901234500035', 3, 1);

-- 标签（front_show=1 才进 GOODS-8 tagList）
INSERT IGNORE INTO items_tags (tag_id, company_id, tag_name, tag_color, font_color, front_show, created) VALUES
  (1, 1, '新品', '#ff5000', '#ffffff', 1, 0),
  (2, 1, '热卖', '#e1251b', '#ffffff', 1, 0);
INSERT IGNORE INTO items_rel_tags (id, tag_id, item_id, company_id) VALUES (1, 1, 1, 1), (2, 2, 3, 1);

-- 服务标签（items_rel_type = type_labels 来源；该表无 created/updated 列）
INSERT IGNORE INTO servicelabels (label_id, company_id, label_name, label_price, service_type, created, updated) VALUES
  (1, 1, '顺丰包邮', 0, 'express', 0, 0),
  (2, 1, '7天无理由', 0, 'refund', 0, 0);
INSERT IGNORE INTO items_rel_type (id, item_id, label_id, label_name, label_price, num_type, num, is_not_limit_num, limit_time) VALUES
  (1, 1, 1, '顺丰包邮', 0, 'plus', 1, 1, 0),
  (2, 1, 2, '7天无理由', 0, 'plus', 1, 1, 0);

-- 预警阈值 + 前台展示配置（GOODS-1 warning_store、GOODS-8 cur/tdk_data）
INSERT IGNORE INTO sys_config (company_id, config_key, config_value, updated) VALUES
  (1, 'item.warning_store', '10', 0),
  (1, 'site.currency', 'CNY', 0),
  (1, 'site.currency_symbol', '¥', 0),
  (1, 'site.currency_rate', '1', 0),
  (1, 'site.tdk.title', 'ECShopX Kotlin 演示商城', 0),
  (1, 'site.tdk.description', 'Kotlin 重写版 ECShopX 前台', 0),
  (1, 'site.tdk.keywords', 'ECShopX,Kotlin,商城', 0);

-- 演示行显式指定主键后，自增序列必须越过它们（否则 GOODS-3 新增会撞 PK）
-- H2 语法；MySQL 部署时改写为 `ALTER TABLE <表名> AUTO_INCREMENT = 100;`
ALTER TABLE items ALTER COLUMN item_id RESTART WITH 100;
ALTER TABLE items_attributes ALTER COLUMN attribute_id RESTART WITH 100;
ALTER TABLE items_attribute_values ALTER COLUMN attribute_value_id RESTART WITH 100;
ALTER TABLE items_rel_attributes ALTER COLUMN id RESTART WITH 100;
ALTER TABLE items_barcode ALTER COLUMN id RESTART WITH 100;
ALTER TABLE items_tags ALTER COLUMN tag_id RESTART WITH 100;
ALTER TABLE items_rel_tags ALTER COLUMN id RESTART WITH 100;
ALTER TABLE servicelabels ALTER COLUMN label_id RESTART WITH 100;
ALTER TABLE items_rel_type ALTER COLUMN id RESTART WITH 100;

-- ===== M0-5 追加：购物车与算价演示数据 =====
-- 金卡会员（grade_id=3，discount=95 即 95 折）：会员等级折扣链路实跑用，
-- 与 user_id=1（普通会员 100=无折扣）配对，正好覆盖"有无折扣"两条算价分支。
INSERT IGNORE INTO members (user_id, company_id, grade_id, mobile, user_card_code, created, updated)
VALUES (2, 1, 3, '13800000002', '80000002', 0, 0);
INSERT IGNORE INTO members_info (user_id, username, name, sex) VALUES (2, '金卡用户', '李四', 2);
INSERT IGNORE INTO point_member (company_id, user_id, point, total_point) VALUES (1, 2, 500, 500);
INSERT IGNORE INTO user_deposit (company_id, user_id, deposit_amount) VALUES (1, 2, 0);

-- 第二张运费模板：按件 8 元、无包邮门槛（item.templates_id=1 仍是默认包邮，本张供购物车/预览按件计费用例）
INSERT IGNORE INTO shipping_templates (template_id, company_id, name, is_free, valuation, status, fee_conf, created, updated)
VALUES (2, 1, '按件 8 元模板', 'n', '1', TRUE, '{"first_fee":8}', 0, 0);

-- ===== M0-6 订单域演示数据（追加，不回改上文）=====
-- 后台订单编辑权限（备注/改价等卖家操作，发货已单独有 orders.delivery）
INSERT IGNORE INTO resources (resource_id, parent_id, resource_name, resource_code, resource_type)
VALUES (7, 4, '订单编辑', 'orders.update', 'actived');
INSERT IGNORE INTO role_rel_resources (role_id, resource_id) VALUES (1, 7);

-- 确认收货后的自动完成天数（发货时算 auto_finish_time；缺省值 10 天写在服务层）
INSERT IGNORE INTO sys_config (company_id, config_key, config_value, updated)
VALUES (1, 'order.auto_finish_days', '10', 0);

-- 会员收货地址（下单可用 address_id 展开，省一组建件参数）
INSERT IGNORE INTO members_address (address_id, user_id, company_id, username, telephone, province, city, county, adrdetail, postal_code, is_def, created, updated)
VALUES
  (1, 1, 1, '张三', '13800000001', '广东省', '深圳市', '南山区', '科技园科苑路 15 号 3 栋 301', '518000', TRUE, 0, 0),
  (2, 2, 1, '李四', '13800000002', '北京市', '北京市', '朝阳区', '望京 SOHO T1 座 1801', '100102', TRUE, 0, 0);

-- ===== M0-7 支付域演示数据（追加于末尾，不回改上文） =====
-- 充值单号段（SeqName.RECHARGE_NO，前缀 CZ）：缺行会让取号直接报错
INSERT IGNORE INTO seq_generator (seq_name, current_value) VALUES ('recharge_no', 0);

-- 储值充值档位（分）：充 100 送 10 / 充 500 送 80，PAY-4 按金额命中档位才送赠金
INSERT IGNORE INTO deposit_recharge_rule (id, company_id, rule_name, recharge_amount, gift_amount, status, created, updated) VALUES
  (1, 1, '充 100 送 10', 10000, 1000, 1, 0, 0),
  (2, 1, '充 500 送 80', 50000, 8000, 1, 0, 0);
ALTER TABLE deposit_recharge_rule ALTER COLUMN id RESTART WITH 100;

-- 线下/渠道支付开关：M0 只放开 mock 与三种本地渠道
INSERT IGNORE INTO sys_config (company_id, config_key, config_value, updated) VALUES
  (1, 'pay.wxpay.enable', '1', 0),
  (1, 'pay.deposit.enable', '1', 0),
  (1, 'pay.point.enable', '1', 0),
  (1, 'pay.offline.enable', '1', 0);

-- ===== M0-8 追加：会员/支撑域权限与演示数据（追加于末尾，不回改上文） =====
-- SUP-1/2/4 后台权限（config 读写分立，运营能改业务参数不等于能改支付开关之外的东西；
-- upload 与 operator.logs 各一条，viewer 账号不挂角色 → 天然用来验 403）
INSERT IGNORE INTO resources (resource_id, parent_id, resource_name, resource_code, resource_type) VALUES
  (8,  0, '系统设置',   'config',        'catalog'),
  (9,  8, '配置查看',   'config.lists',  'actived'),
  (10, 8, '配置修改',   'config.update', 'actived'),
  (11, 0, '图片上传',   'upload.image',  'actived'),
  (12, 0, '操作日志',   'operator.logs', 'actived');
INSERT IGNORE INTO role_rel_resources (role_id, resource_id) VALUES (1, 8), (1, 9), (1, 10), (1, 11), (1, 12);

-- 会员资料补全：演示用户给全字段（收藏/个人中心页要能渲染头像、生日、爱好标签）
UPDATE members_info SET avatar = '/img/avatar-1.png', birthday = '1990-01-01',
    email = 'demo@example.com', industry = '互联网', income = '10000',
    edu_background = 'bachelor', habbit = '[{"name":"数码","ischecked":1},{"name":"运动","ischecked":0}]'
  WHERE user_id = 1;
UPDATE members_info SET avatar = '/img/avatar-2.png', birthday = '1988-06-06',
    email = 'gold@example.com', industry = '金融', income = '20000',
    edu_background = 'master', habbit = '[{"name":"咖啡","ischecked":1}]'
  WHERE user_id = 2;

-- 收藏快照（item_price 是收藏当时的价格，列表出参另给实时价，降价提示靠两者对比）
INSERT IGNORE INTO members_items_fav (fav_id, user_id, company_id, item_id, item_name, item_image, item_price, created) VALUES
  (1, 1, 1, 3, '每日坚果礼盒（旧价）', '/img/nut.jpg', 9900, 0),
  (2, 1, 1, 1, '测试手机 X1', '/img/x1-1.jpg', 199900, 0);
ALTER TABLE members_items_fav ALTER COLUMN fav_id RESTART WITH 100;

-- 浏览足迹（同一 item 只留最新一条，服务层先删后插）
INSERT IGNORE INTO members_browse_history (history_id, user_id, item_id, company_id, created) VALUES
  (1, 1, 3, 1, 0), (2, 1, 1, 1, 0);
ALTER TABLE members_browse_history ALTER COLUMN history_id RESTART WITH 100;

-- 会员个人中心的展示型配置（SUP-1 读写演示 + 前台"我的"页文案）
INSERT IGNORE INTO sys_config (company_id, config_key, config_value, updated) VALUES
  (1, 'member.center.banner', '/img/member-banner.jpg', 0),
  (1, 'site.contact_phone', '400-000-0000', 0);

-- 只存在于全局层（company_id=0）的默认值：SUP-1 合并视图要能读到它，
-- 且它永远不会被接口写进租户层（写只落租户），所以冒烟重跑也不会失配。
INSERT IGNORE INTO sys_config (company_id, config_key, config_value, updated) VALUES
  (0, 'site.member_center_copy', '登录后查看专属价', 0);

-- ===== M1-1 追加：售后/退款权限码与售后配置（追加于末尾，不回改上文） =====
-- 权限分两组的理由：能同意退货（aftersales.review）不该天然具备"把钱退出去"的能力
-- （aftersales.refund_check），线下打款登记（refund.offline）再单列一条 —— 源系统的审核岗/财务岗分立。
INSERT IGNORE INTO resources (resource_id, parent_id, resource_name, resource_code, resource_type) VALUES
  (13, 0,  '售后管理',  'aftersales',              'catalog'),
  (14, 13, '售后列表',  'aftersales.lists',        'actived'),
  (15, 13, '售后详情',  'aftersales.detail',       'actived'),
  (16, 13, '售后审核',  'aftersales.review',       'actived'),
  (17, 13, '退款确认',  'aftersales.refund_check', 'actived'),
  (18, 13, '售后编辑',  'aftersales.update',       'actived'),
  (19, 13, '代客申请',  'aftersales.apply',        'actived'),
  (20, 0,  '退款管理',  'refund',                  'catalog'),
  (21, 20, '退款列表',  'refund.lists',            'actived'),
  (22, 20, '退款详情',  'refund.detail',           'actived'),
  (23, 20, '线下打款',  'refund.offline',          'actived');
INSERT IGNORE INTO role_rel_resources (role_id, resource_id) VALUES
  (1, 13), (1, 14), (1, 15), (1, 16), (1, 17), (1, 18), (1, 19), (1, 20), (1, 21), (1, 22), (1, 23);

-- 售后原因字典（D8：源系统存 redis，本项目读 sys_config，读不到回落服务层内置四条）
-- 回寄期限：商家同意后买家寄回的时限（天），超时由 AftersalesService.autoRefuse 关闭
INSERT IGNORE INTO sys_config (company_id, config_key, config_value, updated) VALUES
  (1, 'aftersales.reason', '["质量问题","商品与描述不符","不想要了","其他原因"]', 0),
  (1, 'aftersales.return_days', '7', 0);


-- ===== M1-2 追加：线下转账审核流权限（追加于末尾，不回改上文） =====
-- 与售后退款同样的岗位分立逻辑：看凭证（offline_payment.lists）与把钱结清（offline_payment.check）
-- 是两条权限码，审核通过等于"承认钱已到账"，不该默认发给所有后台角色。
INSERT IGNORE INTO resources (resource_id, parent_id, resource_name, resource_code, resource_type) VALUES
  (24, 0,  '线下转账', 'offline_payment',       'catalog'),
  (25, 24, '转账凭证', 'offline_payment.lists', 'actived'),
  (26, 24, '凭证审核', 'offline_payment.check', 'actived');
INSERT IGNORE INTO role_rel_resources (role_id, resource_id) VALUES (1, 24), (1, 25), (1, 26);

-- ===== M1-3 追加：营销中心权限 + 券限领 + 演示营销位（追加于末尾，不回改上文） =====
-- 满减满折的"看/改/审"三条码分立：能配活动的人不该自己批自己（reviewed 才参与算价）。
INSERT IGNORE INTO resources (resource_id, parent_id, resource_name, resource_code, resource_type) VALUES
  (27, 0,  '营销中心',   'promotions',               'catalog'),
  (28, 27, '优惠券列表', 'promotions.coupon.lists',  'actived'),
  (29, 27, '优惠券编辑', 'promotions.coupon.update', 'actived'),
  (30, 27, '后台发券',   'promotions.coupon.send',   'actived'),
  (31, 27, '活动列表',   'promotions.marketing.lists',   'actived'),
  (32, 27, '活动编辑',   'promotions.marketing.update',  'actived'),
  (33, 27, '活动审核',   'promotions.marketing.check',   'actived'),
  (34, 27, '秒杀列表',   'promotions.seckill.lists',   'actived'),
  (35, 27, '秒杀编辑',   'promotions.seckill.update',  'actived'),
  (36, 27, '拼团列表',   'promotions.groups.lists',    'actived'),
  (37, 27, '拼团编辑',   'promotions.groups.update',   'actived');
INSERT IGNORE INTO role_rel_resources (role_id, resource_id) VALUES
  (1, 27), (1, 28), (1, 29), (1, 30), (1, 31), (1, 32), (1, 33), (1, 34), (1, 35), (1, 36), (1, 37);

-- 券模板补列取值：老那张"满100减20"限领 2 张；再加一张无门槛 10 元券（领后 30 天有效，走 valid_days 分支）
UPDATE promotions_coupon SET get_limit = 2, instruction = '满100减20，每人限领2张' WHERE coupon_id = 1;
INSERT IGNORE INTO promotions_coupon
  (coupon_id, company_id, coupon_type, coupon_name, discount_fee, min_order_fee, total_num, send_num,
   valid_type, begin_time, end_time, valid_days, get_limit, status)
VALUES (2, 1, 'cash', '新人10元无门槛券', 1000, 0, 500, 0, 'days', 0, 0, 30, 1, TRUE);

-- 演示营销位一律做成"不在窗内"的两态：一条已结束、一条未开始。
-- 原因不是省事：满减活动是**全局算价因子**，一条在窗内的全场满减会把 m05/m06/m07/m11/m12
-- 冒烟脚本里所有金额断言改掉。演示要的活动由后台 MK-A1/A6 现场配（那本身就是被测路径），跑完即清。
INSERT IGNORE INTO promotions_marketing_activity
  (marketing_id, company_id, marketing_type, marketing_name, start_time, end_time,
   use_bound, condition_type, condition_value, canjoin_repeat, free_postage, promotion_tag, check_status, status)
VALUES
  (1, 1, 'full_minus',    '双11 全场满100减20（已结束）', 1609459200, 1640966400,
      0, 'totalfee', '[{"full":"100.00","minus":"20.00"},{"full":"200.00","minus":"50.00"}]',
      FALSE, FALSE, '满减', 'reviewed', TRUE),
  (2, 1, 'full_discount', '全场满100打9折（未开始）', 4102444800, 4102531200,
      0, 'totalfee', '[{"full":"100.00","discount":"90"}]',
      TRUE, TRUE, '满减', 'reviewed', TRUE);

-- 秒杀与拼团的演示行同样放在"已结束"，避免与既有金额断言相撞
INSERT IGNORE INTO promotions_seckill_activity
  (seckill_id, company_id, activity_name, activity_start_time, activity_end_time,
   distributor_id, seckill_type, limit_money, validity_period, disabled)
VALUES (1, 1, '每日坚果 秒杀（已结束）', 1609459200, 1640966400, '1', 'normal', 0, 10, FALSE);
INSERT IGNORE INTO promotions_seckill_rel_goods
  (id, seckill_id, item_id, item_title, activity_price, activity_store, sales_store, limit_num)
VALUES (1, 1, 3, '每日坚果礼盒', 5900, 100, 100, 2);

INSERT IGNORE INTO promotion_groups_activity
  (groups_activity_id, company_id, act_name, goods_id, act_price, person_num, store,
   limit_buy_num, limit_time, begin_time, end_time, robot, disabled)
VALUES (1, 1, '每日坚果 2人拼团（已结束）', 3, 6900, 2, 100, 1, 24, 1609459200, 1640966400, FALSE, FALSE);

-- ===== M1-4 追加：店铺与分销域权限 + 演示店铺审核态 + 店铺运营账号（追加于末尾，不回改上文） =====
-- 七条码分两组，不是把 CRUD 拆细好看：
--   店铺档案侧「看/改/审/开关」四条分立 —— 能改档案的人不该能自己过审，能过审的也不该能关店（§18.7）；
--   商品侧「列表/挂品」两条 —— 挂品改价是店铺运营的活，开店过审是平台招商的活，源系统也确实是两个岗。
INSERT IGNORE INTO resources (resource_id, parent_id, resource_name, resource_code, resource_type) VALUES
  (38, 0,  '店铺与分销', 'distributor',                'catalog'),
  (39, 38, '店铺列表',   'distributor.lists',          'actived'),
  (40, 38, '店铺编辑',   'distributor.update',         'actived'),
  (41, 38, '店铺审核',   'distributor.review',         'actived'),
  (42, 38, '运营开关',   'distributor.status',         'actived'),
  (43, 38, '店铺商品',   'distributor.items_lists',    'actived'),
  (44, 38, '商品挂店',   'distributor.items_update',   'actived');
INSERT IGNORE INTO role_rel_resources (role_id, resource_id) VALUES
  (1, 38), (1, 39), (1, 40), (1, 41), (1, 42), (1, 43), (1, 44);

-- 演示店铺落审核态：seed 建的店本来就是"平台已经开好的"，review_status 留在 FALSE 会让
-- DS-F1/SI-F1 前台面一家都列不出来（前台只认 已审核+启用+营业 三筛齐全）。
UPDATE distribution_distributor SET review_status = TRUE WHERE company_id = 1 AND distributor_id IN (1, 2);

-- 店铺运营账号：distributor01 / shop123（BCrypt 真实哈希，cost=10，jshell spring-security-crypto 生成）
-- operator_type 非 admin ⇒ 数据权限走 operators.distributor_ids（JSON 数组），只能看到 distributor_id=2 这家店；
-- 角色故意不给 review/status，越权用例（§18.8 门槛②）里"能不能碰别家店"与"有没有审核权"是两条独立的线。
INSERT IGNORE INTO roles (role_id, company_id, role_name, role_type, created, updated)
VALUES (2, 1, '店铺运营', 'custom', 0, 0);
INSERT IGNORE INTO role_rel_resources (role_id, resource_id) VALUES
  (2, 5), (2, 38), (2, 39), (2, 40), (2, 43), (2, 44);
INSERT IGNORE INTO operators
  (operator_id, company_id, username, password, operator_type, distributor_ids, created, updated)
VALUES (3, 1, 'distributor01', '$2a$10$XuQCFgc9Z1zSEoix6.8KlOfiSOOY3qZqW5m6Ib4raPxOPplc36ZPS', 'distributor', '[2]', 0, 0);
INSERT IGNORE INTO operator_rel_roles (operator_id, role_id) VALUES (3, 2);

-- ===== M1-4 第二批追加：佣金/提现权限码 + 结算延时配置 + 演示店铺在册负责人（追加于末尾，不回改上文） =====
-- 四条码按"看钱 / 结算 / 收单 / 批单"四个岗分列（docs/04 §18.7）：
--   distribution.logs 给店铺运营（他必须能看自己店的佣金流水），
--   distribution.close 与 withdraw.* 只给平台侧 —— 结算和打款都不能由店里自己点。
-- 45,46（salesman.*）与 51..53（pickup/writeoff）属第三批，这里不提前占位的名字，只按 §18.7 的编号落 47..50。
INSERT IGNORE INTO resources (resource_id, parent_id, resource_name, resource_code, resource_type) VALUES
  (47, 38, '佣金记录', 'distribution.logs',  'actived'),
  (48, 38, '佣金结算', 'distribution.close', 'actived'),
  (49, 38, '提现列表', 'withdraw.lists',     'actived'),
  (50, 38, '提现审核', 'withdraw.check',     'actived');
INSERT IGNORE INTO role_rel_resources (role_id, resource_id) VALUES
  (1, 47), (1, 48), (1, 49), (1, 50),
  -- 店铺运营岗：只给"看自己店佣金"，结算/提现审核一条都不给（越权用例靠这四条码的缺配跑）
  (2, 47);

-- 佣金结算延时（天）：入账时写 plan_close_time = now + delay*86400，到期由 CommissionSettleJob/DL-A3 置已结算。
-- 显式给 7 而不是留空回落默认，是为了让冒烟里"提现先被'余额不足'挡住、结算后才能提"这条链有稳定的观察点。
INSERT IGNORE INTO sys_config (company_id, config_key, config_value, updated) VALUES
  (1, 'commission.close_delay', '7', 0);

-- 演示店铺「在册负责人」：会员 13800000001（user_id=1）是 demo 旗舰店（distributor_id=2）的老板。
-- 本项目 distribution_distributor 没有归属人列（C152），CW-F1 判"这是不是你的店"只认这条行。
INSERT IGNORE INTO distribution_distributor_salesman
  (salesman_id, company_id, distributor_id, shop_id, user_id, mobile, salesman_name, role_id, is_valid, created, updated)
VALUES (1, 1, 2, 0, 1, '13800000001', 'demo 旗舰店老板', 0, 'true', 0, 0);

-- ===== M1-4 第三批追加：自提点/核销权限码 + 演示自提点（追加于末尾，不回改上文） =====
-- §18.7：`pickup.lists`/`pickup.update` 两条（看点 ≠ 建改停点），`order.writeoff` 单独一条 ——
-- 核销是"承认买家已经把货拿走了"，这一下之后单子不能再退也不能再改，
-- 所以它既不能挂在 `order.lists`（看）也不能挂在 `order.update`（改）底下。
INSERT IGNORE INTO resources (resource_id, parent_id, resource_name, resource_code, resource_type) VALUES
  (51, 38, '自提点列表', 'pickup.lists',    'actived'),
  (52, 38, '自提点维护', 'pickup.update',   'actived'),
  (53, 38, '自提核销',   'order.writeoff',  'actived');
INSERT IGNORE INTO role_rel_resources (role_id, resource_id) VALUES
  (1, 51), (1, 52), (1, 53),
  -- 店铺运营岗：能看自己的点、能核销自己店的单，但建/改/停点是平台的活（越权用例靠这条缺配跑）
  (2, 51), (2, 53);

-- 演示店开自提：13800000001 那家 demo 旗舰店（distributor_id=2）与商城总部都要能走自提链，
-- 否则 ORDER-2 传 receipt_type=ziti 一律 422，冒烟里没有可读的自提单。
UPDATE distribution_distributor SET is_ziti = TRUE WHERE company_id = 1 AND distributor_id IN (1, 2);

-- 演示自提点两条，形状正好是 §18.6 的两个分支：
--   1) 归属店自己的点（distributor_id=2、rel_distributor_id=2）→ 只有这家店的前台看得到；
--   2) 平台通用点（distributor_id=0、rel_distributor_id=0）→ 每家店的前台都看得到，
--      而它在工作台列表里只有 admin 型账号可见（店铺账号 scope.require(0) → 404）。
INSERT IGNORE INTO distribution_pickup_location
  (id, company_id, distributor_id, rel_distributor_id, name, province, city, area, address, lng, lat, contract_phone, status, created, updated)
VALUES
  (1, 1, 2, 2, 'demo 旗舰店自提柜台', '浙江省', '杭州市', '西湖区', '文一西路 969 号 A 座 1 层', '120.026000', '30.279000', '057188886666', 1, 0, 0),
  (2, 1, 0, 0, '平台中心仓（全城可取）', '浙江省', '杭州市', '余杭区', '五常大道 1 号 3 号库', '120.043000', '30.275000', '057188880000', 1, 0, 0);

-- ===== M1-4 第三批后半追加：导购权限码 + 演示角色与员工口令（追加于末尾，不回改上文） =====
-- §18.7 的 45/46：看人 ≠ 改人。`salesman.update` 能建/改/调岗/停用，
-- 停用这一条尤其要限：它直接关掉一个人的员工端登录入口。
INSERT IGNORE INTO resources (resource_id, parent_id, resource_name, resource_code, resource_type) VALUES
  (45, 38, '导购列表', 'salesman.lists',  'actived'),
  (46, 38, '导购维护', 'salesman.update', 'actived');
INSERT IGNORE INTO role_rel_resources (role_id, resource_id) VALUES
  (1, 45), (1, 46),
  -- 店铺运营岗：管自己店的人，但管的范围照样受 `operators.distributor_ids` 约束（门槛②同一条线）
  (2, 45), (2, 46);

-- 两个角色正好是 §18.2 SM-A6 的两种归属：
--   1 = 平台通用角色（distributor_id=0，任何一家店建导购时都能选）；
--   2 = demo 旗舰店自己的岗位（只有店 2 选得到，别的店调岗过去是 422）。
INSERT IGNORE INTO distribution_distributor_salesman_role
  (role_id, company_id, distributor_id, role_name, remark, status, created, updated)
VALUES
  (1, 1, 0, '门店导购', '平台通用岗位：每家店都能选', 1, 0, 0),
  (2, 1, 2, '店长', 'demo 旗舰店自己的岗位', 1, 0, 0);

-- 第二条在册导购：会员 13800000002（user_id=2）。他的用途是把「店铺老板提现」与「导购提现」分开——
-- CW-F1 认 distributor_id + user_id，CW-F3 只认 salesman_id，同一个人不能两头提。
INSERT IGNORE INTO distribution_distributor_salesman
  (salesman_id, company_id, distributor_id, shop_id, user_id, mobile, salesman_name, role_id, is_valid, created, updated)
VALUES (2, 1, 2, 0, 2, '13800000002', '小李（门店导购）', 1, 'true', 0, 0);

-- 员工端演示口令：两条在册导购都设成与 `operators.distributor01` 同一枚演示口令的哈希，
-- 冒烟脚本据此登录 `/api/admin/wxapp/login`。哈希是 bcrypt 输出、不是明文，
-- 与上面 operators 那几行的写法同一处惯例（明文口令只出现在演示脚本的拼接串里）。
UPDATE distribution_distributor_salesman SET password = '$2a$10$XuQCFgc9Z1zSEoix6.8KlOfiSOOY3qZqW5m6Ib4raPxOPplc36ZPS'
WHERE company_id = 1 AND salesman_id IN (1, 2);


-- ===== M1-5 追加：积分/储值/评价/发票权限码 + 配置缺省 + 演示抬头（docs/04 §23.4，追加于末尾，不回改上文） =====
-- 54..63 十条仍按「看码 ≠ 改码」分列（§18.7 那条线），另外单列两条与钱直接打交道的：
--   `deposit.recharge`（代客充值，不动支付就动余额）与 `invoice.update`（开票）都只给平台；
--   `datapass.pass`（63）是唯一一个「不判定归属、只决定同一行出全值还是掩码值」的码，挂在系统设置目录下。
-- 64 是给这批看码新开的目录（会员资产）——积分与储值既不属于订单也不属于营销，硬挂到 38「店铺与分销」
-- 会让店铺运营在权限树里看起来"该有"，实际一条都不给。
INSERT IGNORE INTO resources (resource_id, parent_id, resource_name, resource_code, resource_type) VALUES
  (64,  0, '会员资产',       'member_assets',      'catalog'),
  (54, 64, '积分列表',       'point.lists',        'actived'),
  (55, 64, '积分调账',       'point.update',       'actived'),
  (56, 64, '储值流水',       'deposit.lists',      'actived'),
  (57, 64, '充值档位维护',   'deposit.rule.update','actived'),
  (58, 64, '代客充值',       'deposit.recharge',   'actived'),
  (59,  4, '评价列表',       'rate.lists',         'actived'),
  (60,  4, '评价回复',       'rate.reply',         'actived'),
  (61,  4, '发票申请列表',   'invoice.lists',      'actived'),
  (62,  4, '发票处理',       'invoice.update',     'actived'),
  (63,  8, '敏感信息穿透',   'datapass.pass',      'actived');

INSERT IGNORE INTO role_rel_resources (role_id, resource_id) VALUES
  (1, 54), (1, 55), (1, 56), (1, 57), (1, 58), (1, 59), (1, 60), (1, 61), (1, 62), (1, 63), (1, 64),
  -- 店铺运营岗（role 2）只拿四个看数码 + 本店可回复：
  -- 调账、改档位、代客充值、开票、看全值都是平台权力（D57/C167 同一条划分）。
  (2, 54), (2, 56), (2, 59), (2, 60), (2, 61);

-- 配置缺省：值用现库的 '1'/'0' 词表（`pay.*.enable` 那三条就是这个写法），不另起 true/false。
-- 已有的 `pay.point.enable` / `pay.deposit.enable` / `point.deduct.max_ratio` 三键复用，这里不重复插。
INSERT IGNORE INTO sys_config (company_id, config_key, config_value, updated) VALUES
  (1, 'deposit.recharge.point.rate', '0', 0),
  (1, 'invoice.enable', '1', 0),
  (1, 'invoice.types', '["02"]', 0);

-- 演示抬头两条（`members_invoices` 自 M0 建表起零引用，这里给冒烟一个可读的起点）：
-- 个人抬头 + 企业抬头各一条，默认位给企业那条 —— `is_def` 在同一会员下只能有一个 true（INV-F5）。
INSERT IGNORE INTO members_invoices
  (invoices_id, user_id, company_id, invoices_type, name, telephone, tax_number, business_address, bank, bank_account, is_def, created)
VALUES
  (1, 1, 1, 'personal', '张三', '13800000001', NULL, NULL, NULL, NULL, FALSE, 0),
  (2, 1, 1, 'corporate', '杭州演示科技有限公司', '13800000001', '91330100MA27XXXX0B', '杭州市西湖区文一西路 969 号', '演示银行杭州分行', '6222000000000001', TRUE, 0);

-- ===== M1-5 追加：演示商品的可得积分（docs/04 §23.3 PT-R1，追加于末尾，不回改上文） =====
-- `items.point` 建表至今恒为默认 0，于是"每件得几分"这条链在演示数据上从来没有可观测的一格：
-- 建单算出的 `item_point`/`get_points` 都是 0，结清时 C54 直接 return，积分一条流水都不写。
-- 给演示主力商品（item_id=3，PAY/M1 各脚本下单都用它）标 100 分/件，赠分链自此在实跑里看得见。
-- 只 UPDATE 演示行、不改建表默认值：0 才是"这个商品不送积分"的正确默认。
UPDATE items SET point = 100 WHERE company_id = 1 AND item_id = 3;

-- ===== M2-1 追加：开放能力权限码 + 演示密钥（docs/04 §25.5，追加于末尾，不回改上文） =====
-- 65..69 五码分「看 / 改密钥 / 看投递 / 改订阅 / 主动推」四档，另单列一条能"凭空造一次推送"的
-- `webhook.push`：重推与测试事件都会往外打流量，它和"改订阅配置"不是一回事，混在一个码里
-- 就等于给看板上的人开了对外发包的口子。
-- 70 是这批的目录（开放能力）。role 2（店铺运营）**一条都不配**：开发者密钥与推送地址
-- 是整个租户对外的口子，不是某家店的资产（D57/C167 那条划分的延伸）。
INSERT IGNORE INTO resources (resource_id, parent_id, resource_name, resource_code, resource_type) VALUES
  (70,  0, '开放能力',       'openapi_center',      'catalog'),
  (65, 70, '开发者密钥列表', 'openapi.lists',       'actived'),
  (66, 70, '开发者密钥维护', 'openapi.update',      'actived'),
  (67, 70, '订阅与投递查询', 'webhook.lists',       'actived'),
  (68, 70, '订阅维护',       'webhook.update',      'actived'),
  (69, 70, '手动重推与测试', 'webhook.push',        'actived');

INSERT IGNORE INTO role_rel_resources (role_id, resource_id) VALUES
  (1, 65), (1, 66), (1, 67), (1, 68), (1, 69), (1, 70);

-- 演示密钥两把，都是**假密钥**（与那几条 bcrypt 演示口令同一惯例：只用于本地自签自验，不是任何真实凭据）。
-- developer_id=1 属租户 1，与运营面同一个租户，冒烟脚本据此自己算签（脚本必须能独立算出签，
-- 否则它只是在重复实现里自证 —— §25.6 第 4 条）。
-- developer_id=2 属租户 2：库里没有它的 company 行、也没有任何业务数据，存在的唯一理由是把
-- 「租户取自密钥、不取自入参」这条证出来 —— 它读商品得到 0 行而不是报错，
-- 而它在入参里写 company_id=1 必须被 fail-closed 挡成 E4001（§25.1 第 9 道闸门）。
INSERT IGNORE INTO openapi_developer
  (developer_id, company_id, app_key, app_secret, name, remark, status,
   external_base_uri, external_app_key, external_app_secret, created, updated)
VALUES
  (1, 1, 'ak_1a2b3c4d5e6f7081', 'sk_1a2b3c4d5e6f708192a3b4c5d6e7f809',
   '演示对接方', '本地演示密钥，非真实凭据', 1,
   '', 'ek_demo_out_0001', 'esk_2b3c4d5e6f708192a3b4c5d6e7f809', 0, 0),
  (2, 2, 'ak_2b3c4d5e6f708192', 'sk_2b3c4d5e6f708192a3b4c5d6e7f8091a',
   '第二租户对接方（越权证明用）', '库里没有它的业务数据，读什么都是 0 行', 1,
   '', '', '', 0, 0);

-- 演示库里**不种子订阅**：订阅要指向一个真实可达的接收端，
-- 种子一条指向本机某端口的订阅，会让每一次普通演示都攒出一串投递失败记录（`fail` 终态还不排），
-- 冒烟与 e2e 各自起假接收服务器、各自通过 WH-A2 现建现删。

-- ===== M2-2 追加：SystemLink 对接族权限码 + 演示设置 + 演示站点授权（docs/04 §27.5，追加于末尾） =====
-- 71/72 分「看 / 改」两档：看包含调用留痕（含对端发来的业务参数），改是换 token。
-- 目录 76 单列一张。role 2（店铺运营）一条都不配 —— 两把 token 是整个租户对外的口子，
-- 拿到就能替这个租户开站点、改库存（§25.5 那条划分的延伸）。
INSERT IGNORE INTO resources (resource_id, parent_id, resource_name, resource_code, resource_type) VALUES
  (76, 0, '系统对接',     'systemlink_center', 'catalog'),
  (71, 76, '对接设置与留痕', 'systemlink.lists',  'actived'),
  (72, 76, '对接设置维护',   'systemlink.update', 'actived');

INSERT IGNORE INTO role_rel_resources (role_id, resource_id) VALUES
  (1, 71), (1, 72), (1, 76);

-- 演示设置：两把 token 都是**演示值**（与 M2-1 那两把假密钥同一惯例，只用于本地自签自验）。
-- `ip_whitelist` 留空 = 不限制，因为冒烟与 e2e 的来源地址在测试里是 127.0.0.1 与容器地址两种；
-- 白名单那几条判据由 e2e 自己现建现改（改完再改回来），不靠 seed 里的固定值。
INSERT IGNORE INTO systemlink_setting (company_id, is_open, openapi_token, oms_token, ip_whitelist, created, updated)
VALUES (1, 1, 'gy_openapi_demo_token_1', 'gy_oms_demo_token_1', '', 0, 0);

-- 演示站点授权：`eid`/`passport_uid` 是上游（商派云）的租户标识，`ecapi.site.*` 四条按它定位租户。
-- `expired_at` 取 4102444800（2100-01-01）：演示库不该在某个具体日期之后突然"站点过期"。
INSERT IGNORE INTO company_license
  (company_id, eid, passport_uid, issue_id, available_days, active_at, expired_at, goods_code, product_code, source, created, updated)
VALUES (1, 'demo_eid_1', 'demo_shopexid_1', 'shuyun_demo_shopexid_1', 7300, 0, 4102444800, '', '', 'purchased', 0, 0);

-- ===== M2-2 追加：微信开放平台族权限码（docs/04 §27.5，追加于末尾） =====
-- 73/74/75 同 §25.5 的三档划分：读、改凭据与模板、**主动连微信发包**（发消息/取码都是出网动作）。
-- role 2 一条都不配——对外推送口子同 webhook.push 那条划分（§25.5）。
INSERT IGNORE INTO resources (resource_id, parent_id, resource_name, resource_code, resource_type) VALUES
  (77, 0, '微信能力',    'wechat_center',   'catalog'),
  (73, 77, '授权信息与模板列表', 'wechat.lists',  'actived'),
  (74, 77, '绑定与模板维护',     'wechat.update', 'actived'),
  (75, 77, '发消息与取码',       'wechat.push',   'actived');

INSERT IGNORE INTO role_rel_resources (role_id, resource_id) VALUES
  (1, 73), (1, 74), (1, 75), (1, 77);

-- ===== M2-3 追加：互动域权限码（docs/04 §29.1，追加于末尾） =====
-- 78/79 读、80 改（配置与下架是同一把：改配置本身就是上线/下线动作，分两把只会让
-- "只给看不给改"的运营在配置页 seeing 一个改不了的表单）。都挂在既有 promotions 目录（27）下。
-- role 2（店铺运营）一条都不配 —— 转盘配置动的是全店送分口径，同 webhook.push 那条划分。
INSERT IGNORE INTO resources (resource_id, parent_id, resource_name, resource_code, resource_type) VALUES
  (78, 27, '会员签到记录',     'promotions.checkin.lists',   'actived'),
  (79, 27, '大转盘数据与留痕', 'promotions.turntable.lists', 'actived'),
  (80, 27, '大转盘配置与下架', 'promotions.turntable.update','actived');

INSERT IGNORE INTO role_rel_resources (role_id, resource_id) VALUES
  (1, 78), (1, 79), (1, 80);

-- ===== M2-3 批B 追加：砍价域权限码（docs/04 §29.2，追加于末尾） =====
-- 81 读 / 82 改，同挂营销中心目录 27。role 2 不配 —— 能动底价就能送钱（与 point.update 同一划分）。
INSERT IGNORE INTO resources (resource_id, parent_id, resource_name, resource_code, resource_type) VALUES
  (81, 27, '砍价列表与详情', 'promotions.bargain.lists',  'actived'),
  (82, 27, '砍价创建与维护', 'promotions.bargain.update', 'actived');

INSERT IGNORE INTO role_rel_resources (role_id, resource_id) VALUES
  (1, 81), (1, 82);

-- ===== M2-3 批C 追加：人群折扣权限码（docs/04 §29.3，追加于末尾） =====
-- 83 读（配置与优惠日志）/ 84 改。同挂营销中心目录 27。role 2 不配 —— 折扣一动就是
-- 全店结算口径（与 point.update / bargain.update 同一条划分）。
INSERT IGNORE INTO resources (resource_id, parent_id, resource_name, resource_code, resource_type) VALUES
  (83, 27, '人群折扣配置与日志', 'promotions.crowd.lists',  'actived'),
  (84, 27, '人群折扣创建与维护', 'promotions.crowd.update', 'actived');

INSERT IGNORE INTO role_rel_resources (role_id, resource_id) VALUES
  (1, 83), (1, 84);

-- ===== M2-3 批D 追加：次卡（rights）演示数据与权限码（docs/04 §29.4，追加于末尾） =====
-- 服务标签 + 商品绑定（次卡的次数/有效期事实源），全部"发放链公共facts"放 seed：
INSERT IGNORE INTO servicelabels (label_id, company_id, label_name, label_price, service_type, created, updated)
VALUES (1, 1, '健身私教次卡', 0, 'rights', 0, 0);

-- item 4：服务型商品（consume_type='service'），价格 99 元、无运费模板（编码=免邮）
INSERT IGNORE INTO items (item_id, company_id, goods_id, default_item_id, is_default, item_name, item_bn, price, cost_price, market_price, store, sales, approve_status, audit_status, templates_id, item_type, item_category, consume_type, distributor_id, pics, created, updated) VALUES
  (4, 1, 4, 4, TRUE, '核心肌群训练服务', 'SKU-SVC-01', 9900, 3000, 12900, 100, 0, 'onsale', 'approved', 0, 'services', NULL, 'service', 1, '["/img/svc.jpg"]', 0, 0);

-- 绑定：限次卡（5 次）+ 30 天有效期（is_not_limit_num=0 限次；limit_time>0 到期）。
-- 注意 items_rel_type 没有 created/updated 列（发放时刻一次性拷贝进卡行，绑定本身随商品走的活配置）
INSERT IGNORE INTO items_rel_type (item_id, label_id, label_name, label_price, num_type, num, is_not_limit_num, limit_time) VALUES
  (4, 1, '健身私教次卡', 0, 'plus', 5, 0, 30);

-- 权限码 85 读（台账与流水）/ 86 改（手工发放）。同挂营销中心目录 27，role 1 独占；
-- 员工核销走 staffChain（无码，§18.7），运营面补录才要码。
INSERT IGNORE INTO resources (resource_id, parent_id, resource_name, resource_code, resource_type) VALUES
  (85, 27, '次卡台账与流水', 'promotions.rights.lists',  'actived'),
  (86, 27, '次卡手工发放',   'promotions.rights.update', 'actived');

INSERT IGNORE INTO role_rel_resources (role_id, resource_id) VALUES
  (1, 85), (1, 86);

-- ===== M2-3 批E 追加：地区库精简 seed（ecx.jurisdiction.get，C237）=====
-- 直辖市的孩子直接是区（level 2），与省两级结构同树
INSERT IGNORE INTO sys_jurisdiction (id, parent_id, name, level, sort) VALUES
  (1, 0, '浙江省', 1, 1),
  (2, 0, '江苏省', 1, 2),
  (3, 0, '上海市', 1, 3),
  (4, 1, '杭州市', 2, 1), (5, 1, '宁波市', 2, 2), (6, 1, '温州市', 2, 3),
  (7, 2, '南京市', 2, 1), (8, 2, '苏州市', 2, 2),
  (19, 3, '黄浦区', 2, 1), (20, 3, '浦东新区', 2, 2),
  (10, 4, '西湖区', 3, 1), (11, 4, '滨江区', 3, 2), (12, 4, '余杭区', 3, 3),
  (13, 5, '海曙区', 3, 1), (14, 5, '鄞州区', 3, 2),
  (15, 6, '鹿城区', 3, 1),
  (16, 7, '玄武区', 3, 1), (17, 7, '鼓楼区', 3, 2),
  (18, 8, '姑苏区', 3, 1);

-- ===== M2-4 批F 追加：账期设置 seed（§29.6）=====
INSERT IGNORE INTO statement_period_setting (id, company_id, merchant_type, period_type, created, updated) VALUES
  (1, 1, 'distributor', 'daily', 0, 0),
  (2, 1, 'supplier', 'daily', 0, 0);

-- 权限码 88..91：结算读/写（确认）、渠道配置读/写。挂 M2-4 新开的目录 87（生产化）。
INSERT IGNORE INTO resources (resource_id, parent_id, resource_name, resource_code, resource_type) VALUES
  (87, 0,  '生产化中心',   'production',               'catalog'),
  (88, 87, '结算对账单读', 'settle.statements.lists',  'actived'),
  (89, 87, '结算确认',     'settle.statements.update', 'actived'),
  (90, 87, '支付渠道配置读', 'pay.config.lists',       'actived'),
  (91, 87, '支付渠道配置写', 'pay.config.update',      'actived');
INSERT IGNORE INTO role_rel_resources (role_id, resource_id) VALUES
  (1, 88), (1, 89), (1, 90), (1, 91), (1, 87);
