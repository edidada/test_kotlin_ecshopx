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
