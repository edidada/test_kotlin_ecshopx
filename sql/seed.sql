-- =====================================================================
-- kotlin-ecshopx 初始化数据脚本（H2, 配合 schema.sql 使用）
-- 独立部署模式：company_id 固定为 1（对应 PHP config/common.php system_companys_id）
-- =====================================================================
SET SCHEMA ECX;

-- 租户
INSERT IGNORE INTO company (company_id, company_name, status, system_is_saas, created, updated)
VALUES (1, 'ECShopX Kotlin 演示租户', 'normal', FALSE, 0, 0);

-- 运营账号：admin / admin123（password 为 BCrypt 占位，首次启动由 DataInitializer 重算）
INSERT IGNORE INTO operators (operator_id, company_id, username, password, operator_type, created, updated)
VALUES (1, 1, 'admin', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVEFDa', 'admin', 0, 0);

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

-- 号段行已在 schema.sql 中初始化；此处仅推进起始值
UPDATE seq_generator SET current_value = 100000 WHERE seq_name IN ('order_no','trade_no','refund_no','aftersales_no');
