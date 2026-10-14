# ECShopX PHP 全量路由清单（迁移参考附录）

共 **2199** 条端点，由 `docs/_analysis/extract_routes.py` 从 `thirdparty/ECShopX/routes/**` 机械提取。

Kotlin 重写的完整 URL 迁移对照表；接口逐条的入参/出参规格见 [URL 列表文档](./04-URL列表文档.md)（核心接口）与各模块详细设计。


## A. 商家/运营后台 API（1392 条）

> 基址 `/api/…`，鉴权 provider `jwt`（运营人员/商家 JWT）


### `routes/api/CardVoucher.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| POST | `/api/discountcard` | 添加优惠券 | Action\DiscountCard@createDiscountCard | jwt | api.auth,activated,shoplog |
| DELETE | `/api/discountcard` | 删除卡券 | Action\DiscountCard@deleteDiscountCard | jwt | api.auth,activated,shoplog |
| PATCH | `/api/discountcard` | 修改卡券内容 | Action\DiscountCard@updateDiscountCard | jwt | api.auth,activated,shoplog |
| GET | `/api/discountcard/get` | 获取卡券明细 | Action\DiscountCard@getDiscountCardDetail | jwt | api.auth,activated,shoplog |
| GET | `/api/discountcard/list` | 获取卡券列表 | Action\DiscountCard@getDiscountCardList | jwt | api.auth,activated,shoplog |
| GET | `/api/discountcard/detail/list` | 获取卡券领取列表以及使用明细 | Action\DiscountCardDetail@getDiscountCardDetail | jwt | datapass |
| GET | `/api/effectiveDiscountcard/list` | 获取有效卡券列表 | Action\DiscountCard@getEffectiveDiscountCardList | jwt | api.auth,activated,shoplog |
| POST | `/api/discountcard/updatestore` | 修改卡券库存 | Action\DiscountCard@updateCardStore | jwt | api.auth,activated,shoplog |
| POST | `/api/discountcard/uploadToWechat` | 卡券推送至微信 | Action\DiscountCard@uploadToWechatCard | jwt | api.auth,activated,shoplog |
| GET | `/api/discountcard/listdata` | 获取优惠券列表 | Action\DiscountCard@getEasyDiscountList | jwt | api.auth,activated,shoplog |
| GET | `/api/discountcard/couponGrantSetting` | 获取优惠券发放管理配置信息 | Action\DiscountCard@getCouponCardGrantSetting | jwt | api.auth,activated,shoplog |
| POST | `/api/discountcard/couponGrantSetting` | 保存优惠券发放管理配置信息 | Action\DiscountCard@setCouponCardGrantSetting | jwt | api.auth,activated,shoplog |
| POST | `/api/discountcard/consume` | 兑换券核销 | Action\DiscountCard@consumeExCard | jwt | api.auth,activated,shoplog |
| PUT | `/api/membercard` | 更新会员卡设置 | Action\MemberCard@setMemberCard | jwt | api.auth,activated,shoplog |
| GET | `/api/membercard` | 获取会员卡信息 | Action\MemberCard@getMemberCard | jwt | api.auth,activated,shoplog |
| PUT | `/api/membercard/grade` | 更新会员卡等级 | Action\MemberCard@updateMembercardGrade | jwt | api.auth,activated,shoplog |
| GET | `/api/membercard/defaultGrade` | 获取会员卡默认等级 | Action\MemberCard@getDefaultGrade | jwt | api.auth,activated,shoplog |
| GET | `/api/membercard/grades` | 获取会员等级列表 | Action\MemberCard@getGradeList | jwt | api.auth,activated,shoplog |
| PUT | `/api/membercard/vipgrade` | 保存付费会员等级卡 | Action\VipGradeController@addDataVipGrade | jwt | api.auth,activated,shoplog |
| GET | `/api/membercard/vipgrade` | 获取付费会员等级卡列表 | Action\VipGradeController@listDataVipGrade | jwt | api.auth,activated,shoplog |
| GET | `/api/vipgrade/order` | 获取会员卡购买记录 | Action\VipGradeController@listDataVipGradeOrder | jwt | datapass |
| GET | `/api/vipgrades/uselist` | 获取指定用户所有的付费会员等级到期时间 | Action\VipGradeController@getAllUserVipGrade | jwt | api.auth,activated,shoplog |
| PUT | `/api/vipgrades/active_delay` | 主动延期付费会员 | Action\VipGradeController@receiveMemberCard | jwt | api.auth,activated,shoplog |
| PUT | `/api/vipgrades/batch_active_delay` | 批量主动延期付费会员 | Action\VipGradeController@batchReceiveMemberCard | jwt | api.auth,activated,shoplog |
| GET | `/api/voucher/package/list` | 获取卡券包列表 | Action\Package@getList | jwt | api.auth,activated,shoplog |
| GET | `/api/voucher/package/details` | 获取卡券包详情 | Action\Package@getDetails | jwt | api.auth,activated,shoplog |
| POST | `/api/voucher/package/check_grade_limit` | 校验卡券包等级限制 | Action\Package@checkCardPackageGradeLimit | jwt | api.auth,activated,shoplog |
| GET | `/api/voucher/package/get_receives_log` | 卡券包领取日志 | Action\Package@getPackageReceivesLog | jwt | datapass |
| POST | `/api/voucher/package` | 创建卡券包 | Action\Package@createPackage | jwt | api.auth,activated,shoplog |
| PATCH | `/api/voucher/package` | 编辑卡券包 | Action\Package@editPackage | jwt | api.auth,activated,shoplog |
| DELETE | `/api/voucher/package` | 删除卡券包 | Action\Package@deletePackage | jwt | api.auth,activated,shoplog |
| GET | `/api/getUserCardList` | 获取用户可用的优惠券 | Action\UserDiscount@getUserCardList | jwt | api.auth,activated |

### `routes/api/adapay.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| GET | `/api/adapay/is_open` | 是否开通 | Action\OpenAccount@isOpen | jwt | api.auth,activated,shoplog |
| GET | `/api/adapay/withdrawset` | 获取提现设置 | Action\AdapayWithdrawSet@index | jwt | activated |
| POST | `/api/adapay/withdrawset` | 提现设置 | Action\AdapayWithdrawSet@save | jwt | activated |
| GET | `/api/adapay/drawcash/getList` | 提现记录 | Action\AdapayDrawCash@getList | jwt | activated,datapass |
| POST | `/api/adapay/withdraw` | 汇付提现申请 | Action\AdapayDrawCash@withdraw | jwt | activated |
| GET | `/api/adapay/trade/list` | 交易单列表 | Action\AdapayTrade@getTradelist | jwt | activated |
| GET | `/api/adapay/trade/info/{trade_id}` | 交易单详情 | Action\AdapayTrade@getTradeInfo | jwt | activated |
| GET | `/api/adapay/trade/exportdata` | 导出交易单列表 | Action\ExportData@exportTradeData | jwt | activated |
| GET | `/api/adapay/distributor/list` | 获取店铺列表 | Action\AdapayTrade@getDistributorList | jwt | activated |
| POST | `/api/adapay/merchant_entry/create` | 创建开户进件申请 | Action\OpenAccount@merchantEntryCreate | jwt | api.auth,activated,shoplog |
| GET | `/api/adapay/merchant_entry/info` | 开户进件详情 | Action\OpenAccount@merchantEntryInfo | jwt | api.auth,activated,shoplog |
| GET | `/api/adapay/member/auditState` | 用户对象审核状态 | Action\Member@getAuditState | jwt | api.auth,activated,shoplog |
| GET | `/api/adapay/member/setValid` | 用户进入结算中心状态 | Action\Member@setValid | jwt | api.auth,activated,shoplog |
| GET | `/api/adapay/member/get` | 获取个人用户对象 | Action\Member@get | jwt | api.auth,activated,shoplog |
| POST | `/api/adapay/member/create` | 创建个人用户对象 | Action\Member@create | jwt | api.auth,activated,shoplog |
| POST | `/api/adapay/member/modify` | 修改个人用户对象(未开户) | Action\Member@modify | jwt | api.auth,activated,shoplog |
| POST | `/api/adapay/member/update` | 更新个人用户对象 | Action\Member@update | jwt | api.auth,activated,shoplog |
| GET | `/api/adapay/corp_member/get` | 获取企业用户对象 | Action\CorpMember@get | jwt | api.auth,activated,shoplog |
| POST | `/api/adapay/corp_member/create` | 创建企业用户对象 | Action\CorpMember@create | jwt | api.auth,activated,shoplog |
| POST | `/api/adapay/corp_member/modify` | 修改企业用户对象(未开户) | Action\CorpMember@modify | jwt | api.auth,activated,shoplog |
| POST | `/api/adapay/corp_member/update` | 更新企业用户对象 | Action\CorpMember@update | jwt | api.auth,activated,shoplog |
| GET | `/api/adapay/bank/list` | 获取结算银行列表 | Action\OpenAccount@getBanksLists | jwt | api.auth,activated,shoplog |
| GET | `/api/adapay/regions/list` | 获取省市列表(四位码) | Action\OpenAccount@getRegionsLists | jwt | api.auth,activated,shoplog |
| GET | `/api/adapay/regions_third/list` | 获取省市区列表(六位码或九位码) | Action\OpenAccount@getRegionsThirdLists | jwt | api.auth,activated,shoplog |
| POST | `/api/adapay/merchant_resident/create` | 商户入驻申请 | Action\OpenAccount@merchantResidentCreate | jwt | api.auth,activated,shoplog |
| GET | `/api/adapay/merchant_resident/info` | 商户入驻详情 | Action\OpenAccount@merchantResidentInfo | jwt | api.auth,activated,shoplog |
| GET | `/api/adapay/wx_business_cat/list` | 获取微信经营类目 | Action\OpenAccount@getWxBusinessCatList | jwt | api.auth,activated,shoplog |
| GET | `/api/adapay/alipay_industry_cat/list` | 获取支付宝行业类目 | Action\OpenAccount@getAlipayIndustryCatList | jwt | api.auth,activated,shoplog |
| POST | `/api/adapay/license/upload` | 上传商户证照 | Action\OpenAccount@uploadLicense | jwt | api.auth,activated,shoplog |
| POST | `/api/adapay/license_submit/create` | 提交商户证照 | Action\OpenAccount@submitLicense | jwt | api.auth,activated,shoplog |
| GET | `/api/adapay/license_submit/info` | 商户证照详情 | Action\OpenAccount@submitLicenseInfo | jwt | api.auth,activated,shoplog |
| GET | `/api/adapay/open_account/step` | 商户开户步骤 | Action\OpenAccount@openAccountStep | jwt | datapass |
| GET | `/api/adapay/generate/key` | 生成RAS密钥 | Action\OpenAccount@generateKey | jwt | api.auth,activated,shoplog |
| GET | `/api/adapay/other/cat` | 费率 入驻 商户分类 | Action\OpenAccount@otherCat | jwt | api.auth,activated,shoplog |
| GET | `/api/adapay/sub_approve/list` | 子商户审批列表 | Action\SubMerchant@subApproveLists | jwt | api.auth,activated,shoplog |
| GET | `/api/adapay/sub_approve/info/{id}` | 子商户审批详情 | Action\SubMerchant@subApproveInfo | jwt | datapass |
| POST | `/api/adapay/sub_approve/save_split_ledger` | 子商户审批保存分账信息 | Action\SubMerchant@saveSplitLedger | jwt | api.auth,activated,shoplog |
| POST | `/api/adapay/sub_approve/draw_limit` | 保存子商户提现限额 | Action\SubMerchant@setDrawLimit | jwt | api.auth,activated,shoplog |
| GET | `/api/adapay/sub_approve/draw_limit` | 获取子商户提现限额 | Action\SubMerchant@getDrawLimit | jwt | api.auth,activated,shoplog |
| POST | `/api/adapay/sub_approve/draw_cash_config` | 保存子商户提现限额 | Action\SubMerchant@setDrawCashConfig | jwt | api.auth,activated,shoplog |
| GET | `/api/adapay/sub_approve/draw_cash_config` | 获取子商户提现限额 | Action\SubMerchant@getDrawCashConfig | jwt | api.auth,activated,shoplog |
| GET | `/api/adapay/dealer/list` | 经销商列表 | Action\Dealer@dealerList | jwt | datapass |
| GET | `/api/adapay/dealer/distributors` | 经销商关联店铺列表 | Action\Dealer@distributorList | jwt | datapass |
| GET | `/api/adapay/dealer/{id}` | 经销商详情 | Action\Dealer@dealerInfo | jwt | datapass |
| PUT | `/api/adapay/dealer/disable` | 经销商开启 禁用 | Action\Dealer@openOrDisable | jwt | api.auth,activated,shoplog |
| PUT | `/api/adapay/dealer/rel` | 经销商关联店铺 | Action\Dealer@dealerRelDistributor | jwt | api.auth,activated,shoplog |
| GET | `/api/adapay/log/list` | 操作日志列表 | Action\AdapayLog@getList | jwt | api.auth,activated,shoplog |
| PUT | `/api/adapay/dealer/reset/{operatorId}` | 经销商重置密码 | Action\Dealer@resetPassword | jwt | api.auth,activated,shoplog |
| PUT | `/api/adapay/dealer/update/{operatorId}` | 经销商端账号编辑 | Action\Dealer@update | jwt | api.auth,activated,shoplog |
| DELETE | `/api/adapay/dealer/sub/del/{operatorId}` | 删除经销商子账号 | Action\Dealer@delDealerSub | jwt | api.auth,activated,shoplog |
| GET | `/api/adapay/member/list` | adapay开户列表(店铺端 经销商端) | Action\Member@lists | jwt | datapass |
| GET | `/api/adapay/dealer/dealer_parent/get` | 获取经销商主账号id | Action\Dealer@getDealerParentId | jwt | api.auth,activated,shoplog |
| POST | `/api/adapay/callback` |  | Action\CallBack@handle |  |  |

### `routes/api/aftersales.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| GET | `/api/aftersales` | 获取售后列表 | Action\Aftersales@getAftersalesList | jwt | datapass |
| GET | `/api/aftersales/logExport` | 导出售后列表 | Action\Aftersales@logExport | jwt | api.auth,activated,shoplog |
| GET | `/api/aftersales/{aftersales_bn}` | 获取售后详情 | Action\Aftersales@getAftersalesDetail | jwt | api.auth,activated,shoplog |
| POST | `/api/aftersales/review` | 售后审核 | Action\Aftersales@aftersalesReview | jwt | api.auth,activated,shoplog |
| POST | `/api/aftersales/refundCheck` | 售后退款审核 | Action\Aftersales@refundCheck | jwt | api.auth,activated,shoplog |
| GET | `/api/aftersales/reason/list` | 售后原因列表获取 | Action\Reason@getSreasonList | jwt | api.auth,activated,shoplog |
| POST | `/api/aftersales/reason/save` | 售后原因列表保存 | Action\Reason@Saveset | jwt | api.auth,activated,shoplog |
| GET | `/api/aftersales/financial/export` | 导出售后报表 | Action\Aftersales@financialExport | jwt | api.auth,activated,shoplog |
| GET | `/api/aftersales/remind/detail` | 售后提醒内容获取 | Action\Aftersales@getRemind | jwt | api.auth,activated,shoplog |
| POST | `/api/aftersales/remind` | 售后提醒内容设置 | Action\Aftersales@setRemind | jwt | api.auth,activated,shoplog |
| PUT | `/api/aftersales/remark` | 更新售后备注 | Action\Aftersales@updateRemark | jwt | api.auth,activated,shoplog |
| POST | `/api/aftersales/apply` | 创建售后申请 | Action\Aftersales@apply | jwt | api.auth,activated,shoplog |
| POST | `/api/aftersales/sendback` | 售后管理员填写寄回信息 | Action\Aftersales@sendback | jwt | api.auth,activated,shoplog |
| GET | `/api/refund` | 获取退款单列表 | Action\Refund@getRefundList | jwt | api.auth,activated,shoplog |
| GET | `/api/refund/detail/{refund_bn}` | 获取退款单详情 | Action\Refund@getRefundsDetail | jwt | api.auth,activated,shoplog |
| GET | `/api/refund/logExport` | 导出退款单列表 | Action\Refund@logExport | jwt | api.auth,activated,shoplog |
| GET | `/api/refund/offline/bank` | 获取线下退款银行信息 | Action\Refund@getOfflineBank | jwt | api.auth,activated,shoplog |
| POST | `/api/refund/offline` | 线下退款 | Action\Refund@saveOfflineRefund | jwt | api.auth,activated,shoplog |

### `routes/api/aliminiapp.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| GET | `/api/aliminiapp/setting/info` | 获取支付宝小程序设置 | Action\AliMiniAppSettingController@actionInfo | jwt | activated |
| POST | `/api/aliminiapp/setting/save` | 保存支付宝小程序设置 | Action\AliMiniAppSettingController@actionSave | jwt | activated |

### `routes/api/aliyunsms.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| GET | `/api/aliyunsms/config` | 基础配置 | Action\Setting@getConfig | jwt | activated |
| POST | `/api/aliyunsms/config` | 基础配置 | Action\Setting@setConfig | jwt | activated |
| GET | `/api/aliyunsms/status` | 短信启用/关闭 | Action\Setting@getStatus | jwt | activated |
| POST | `/api/aliyunsms/status` | 短信启用/关闭 | Action\Setting@setStatus | jwt | activated |
| GET | `/api/aliyunsms/sign/list` | 签名列表 | Action\Sign@getList | jwt | activated |
| GET | `/api/aliyunsms/sign/info` | 签名详情 | Action\Sign@getInfo | jwt | activated |
| POST | `/api/aliyunsms/sign/add` | 新增签名 | Action\Sign@addSign | jwt | activated |
| POST | `/api/aliyunsms/sign/modify` | 修改签名 | Action\Sign@modifySign | jwt | activated |
| DELETE | `/api/aliyunsms/sign/delete/{id}` | 删除签名 | Action\Sign@deleteSign | jwt | activated |
| POST | `/api/aliyunsms/sign/sync` | 同步签名 | Action\Sign@syncSign | jwt | activated |
| GET | `/api/aliyunsms/template/list` | 模板列表 | Action\Template@getList | jwt | activated |
| GET | `/api/aliyunsms/template/info` | 模板详情 | Action\Template@getInfo | jwt | activated |
| POST | `/api/aliyunsms/template/add` | 新增模板 | Action\Template@addTemplate | jwt | activated |
| POST | `/api/aliyunsms/template/modify` | 修改模板 | Action\Template@modifyTemplate | jwt | activated |
| POST | `/api/aliyunsms/template/sync` | 同步模板 | Action\Template@syncTemplate | jwt | activated |
| DELETE | `/api/aliyunsms/template/delete/{id}` | 删除模板 | Action\Template@deleteTemplate | jwt | activated |
| GET | `/api/aliyunsms/scene/list` | 场景列表 | Action\Scene@getList | jwt | activated |
| GET | `/api/aliyunsms/scene/simpleList` | 模板页下拉场景列表 | Action\Scene@getSimpleList | jwt | activated |
| GET | `/api/aliyunsms/scene/detail` | 模板页场景明细 | Action\Scene@getDetail | jwt | activated |
| POST | `/api/aliyunsms/scene/addItem` | 添加场景实例 | Action\Scene@addItem | jwt | activated |
| GET | `/api/aliyunsms/scene/enableItem` | 启用场景实例 | Action\Scene@enableItem | jwt | activated |
| GET | `/api/aliyunsms/scene/disableItem` | 停用场景实例 | Action\Scene@disableItem | jwt | activated |
| DELETE | `/api/aliyunsms/scene/deleteItem/{id}` | 移除场景实例 | Action\Scene@deleteItem | jwt | activated |
| GET | `/api/aliyunsms/record/list` | 短信记录列表 | Action\Record@getList | jwt | activated,datapass |
| POST | `/api/aliyunsms/task/add` | 添加群发任务 | Action\Task@addTask | jwt | activated |
| POST | `/api/aliyunsms/task/modify` | 编辑群发任务 | Action\Task@modifyTask | jwt | activated |
| GET | `/api/aliyunsms/task/list` | 群发任务列表 | Action\Task@getList | jwt | activated |
| GET | `/api/aliyunsms/task/info` | 群发任务详情 | Action\Task@getInfo | jwt | activated |
| POST | `/api/aliyunsms/task/revoke` | 群发任务撤销 | Action\Task@revokeTask | jwt | activated |

### `routes/api/auth.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| POST | `/api/operator/login` | 登录 | Action\AuthController@login |  | shoplogin |
| POST | `/api/operator/getLevel` | 获取登录验证等级 | Action\AuthController@getLevel |  | shoplogin |
| GET | `/api/operator/authorizeurl` | 登陆获取oauth链接 | Action\Operators@getOuthorizeurl |  |  |
| GET | `/api/operator/oauth/logout` | 获取oauth登出链接 | Action\Operators@getOauthLogouturl |  |  |
| POST | `/api/operator/oauth/login` |  | closure |  | shoplogin |
| POST | `/api/operator/workwechat/oauth/login` | 企业微信oauth登录 | Action\WorkWechat@login |  | shoplogin |
| POST | `/api/operator/workwechat/bind_mobile` | 绑定企业微信手机号 | Action\WorkWechat@bindMobile |  | shoplogin |
| GET | `/api/operator/workwechat/authorizeurl` | 获取企业微信oauth登录链接 | Action\WorkWechat@getWorkwechatOuthorizeurl |  | shoplogin |
| POST | `/api/operator/wechat/oauth/login` | 微信oauth登录 | Action\Wxapp@login |  | shoplogin |
| POST | `/api/operator/wechat/lite/login` | 小程序登录 | Action\Wxapp@wxLiteLogin |  | shoplogin |
| POST | `/api/operator/wechat/bind_mobile` | 绑定微信手机号 | Action\Wxapp@bindMobile |  | shoplogin |
| POST | `/api/operator/wechat/bind_account` | 绑定账号 | Action\Wxapp@bindAccountLogin |  | shoplogin |
| GET | `/api/operator/wechat/authorizeurl` | 获取微信oauth登录链接 | Action\Wxapp@getWechatOuthorizeurl |  | shoplogin |
| POST | `/api/operator/wechat/sms/code` | 发送手机短信验证码 | Action\Wxapp@getSmsCode |  | shoplogin |
| POST | `/api/operator/wechat/distributor/js/config` | 前台获取JsSDK | Action\Wxapp@getDistributorJsConfig |  | shoplogin |
| GET | `/api/token/refresh` |  | closure |  | jwt.refresh |
| GET | `/api/token/invalidate` |  | closure |  |  |
| POST | `/api/operator/shuyun/login` |  | closure |  | shoplogin |
| GET | `/api/operator/shopex-bind/status` | Shopex绑定状态 | Action\ShopexBind@status | jwt | api.auth,shoplog |
| POST | `/api/operator/shopex-bind` | Shopex账号绑定 | Action\ShopexBind@bind | jwt | api.auth,shoplog |

### `routes/api/bspay.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| GET | `/api/bspay/user/audit_state` | 用户对象审核状态 | Action\User@getAuditState | jwt | api.auth,activated,shoplog |
| GET | `/api/bspay/user_indv/get` | 获取个人用户对象 | Action\UserIndv@get | jwt | api.auth,activated,shoplog |
| POST | `/api/bspay/user_indv/create` | 创建个人用户对象 | Action\UserIndv@create | jwt | api.auth,activated,shoplog |
| POST | `/api/bspay/user_indv/modify` | 修改个人用户对象(未开户) | Action\UserIndv@modify | jwt | api.auth,activated,shoplog |
| POST | `/api/bspay/user_indv/update` | 更新个人用户对象 | Action\UserIndv@update | jwt | api.auth,activated,shoplog |
| GET | `/api/bspay/user_ent/get` | 获取企业用户对象 | Action\UserEnt@get | jwt | api.auth,activated,shoplog |
| POST | `/api/bspay/user_ent/create` | 创建企业用户对象 | Action\UserEnt@create | jwt | api.auth,activated,shoplog |
| POST | `/api/bspay/user_ent/modify` | 修改企业用户对象(未开户) | Action\UserEnt@modify | jwt | api.auth,activated,shoplog |
| POST | `/api/bspay/user_ent/update` | 更新企业用户对象 | Action\UserEnt@update | jwt | api.auth,activated,shoplog |
| GET | `/api/bspay/sub_approve/list` | 斗拱子商户审批列表 | Action\SubUser@subApproveLists | jwt | api.auth,activated,shoplog |
| GET | `/api/bspay/sub_approve/info/{id}` | 子商户审批详情 | Action\SubUser@subApproveInfo | jwt | datapass |
| POST | `/api/bspay/sub_approve/save_audit` | 斗拱子商户审批保存 | Action\SubUser@saveAudit | jwt | api.auth,activated,shoplog |
| GET | `/api/bspay/regions` | 获取二级所有地区 | Action\User@getRegions | jwt | api.auth,activated,shoplog |
| GET | `/api/bspay/regions/third` | 获取三级所有地区 | Action\User@getRegionsThird | jwt | api.auth,activated,shoplog |
| GET | `/api/bspay/trade/list` | 交易单列表 | Action\Trade@getTradelist | jwt | activated |
| GET | `/api/bspay/trade/info/{trade_id}` | 交易单详情 | Action\Trade@getTradeInfo | jwt | activated |
| GET | `/api/bspay/trade/exportdata` | 导出交易单列表 | Action\ExportData@exportTradeData | jwt | activated |
| GET | `/api/bspay/withdraw/balance` | 获取提现余额 | Action\Withdraw@getBalance | jwt | activated |
| POST | `/api/bspay/withdraw/apply` | 申请提现 | Action\Withdraw@apply | jwt | activated |
| GET | `/api/bspay/withdraw/lists` | 获取提现记录列表 | Action\Withdraw@lists | jwt | activated |
| POST | `/api/bspay/withdraw/audit` | 审核提现申请 | Action\Withdraw@audit | jwt | activated |
| POST | `/api/bspay/withdraw/huifu` | 汇付取现接口 | Action\Withdraw@huifuWithdraw | jwt | activated |
| GET | `/api/bspay/withdraw/exportdata` | 导出提现记录 | Action\ExportData@exportWithdrawData | jwt | activated,datapass |
| POST | `/api/bspay/callback/{eventType}` |  | Action\CallBack@handle |  |  |

### `routes/api/chinaumspay.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| GET | `/api/division/list` | 获取分账单列表 | Action\Division@getList | jwt | api.auth,activated,shoplog |
| GET | `/api/division/detail/list` | 获取分账单详情列表 | Action\Division@getDetailList | jwt | api.auth,activated,shoplog |
| GET | `/api/division/errorlog/list` | 获取分账失败列表 | Action\Division@errorlogList | jwt | api.auth,activated,shoplog |
| PUT | `/api/division/errorlog/resubmit/{id}` | 分账失败重试 | Action\Division@errrorlogResubmit | jwt | api.auth,activated,shoplog |
| GET | `/api/division/exportdata` | 分账单导出 | Action\Division@exportDivisionData | jwt | api.auth,activated,shoplog |
| GET | `/api/division/detail/exportdata` | 分账单明细导出 | Action\Division@exportDivisionDetailData | jwt | api.auth,activated,shoplog |

### `routes/api/comments.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| POST | `/api/comment` | 创建评论 | Action\Comments@createComment | jwt | api.auth,activated,shoplog |
| PATCH | `/api/comment/{comment_id}` | 更新评论 | Action\Comments@updateComment | jwt | api.auth,activated,shoplog |
| GET | `/api/comments` | 获取评论列表 | Action\Comments@getComments | jwt | api.auth,activated,shoplog |

### `routes/api/community.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| GET | `/api/community/chief/apply_fields` | 配置字段列表 | Action\CommunityChiefApplyFields@list | jwt | api.auth,activated,shoplog |
| POST | `/api/community/chief/apply_field` | 创建字段 | Action\CommunityChiefApplyFields@create | jwt | api.auth,activated,shoplog |
| POST | `/api/community/chief/apply_field/switch/{id}` | 配置字段开关 | Action\CommunityChiefApplyFields@switch | jwt | api.auth,activated,shoplog |
| POST | `/api/community/chief/apply_field/{id}` | 修改字段 | Action\CommunityChiefApplyFields@update | jwt | api.auth,activated,shoplog |
| DELETE | `/api/community/chief/apply_field/{id}` | 删除字段 | Action\CommunityChiefApplyFields@delete | jwt | api.auth,activated,shoplog |
| GET | `/api/community/chief/apply/wxaCode` | 团长申请页小程序码 | Action\CommunityChief@getWxaCode | jwt | api.auth,activated,shoplog |
| GET | `/api/community/chief/apply/list` | 团长申请列表 | Action\CommunityChief@getApplyList | jwt | api.auth,activated,shoplog |
| GET | `/api/community/chief/apply/info/{apply_id}` | 团长申请信息 | Action\CommunityChief@getApplyInfo | jwt | api.auth,activated,shoplog |
| POST | `/api/community/chief/approve/{apply_id}` | 团长申请审批 | Action\CommunityChief@approve | jwt | api.auth,activated,shoplog |
| POST | `/api/community/chief/setMemberCommunity` | 设置团长 | Action\CommunityChief@setMemberCommunity | jwt | api.auth,activated,shoplog |
| GET | `/api/community/chief/list` | 团长列表 | Action\CommunityChief@getChiefList | jwt | api.auth,activated,shoplog |
| GET | `/api/community/chief/{chief_id}` | 团长列表 | Action\CommunityChief@getChiefInfo | jwt | api.auth,activated,shoplog |
| GET | `/api/community/rebate/count` | 团长业绩列表 | Action\CommunityChiefCashWithdrawal@getChiefRebateCount | jwt | api.auth,activated,shoplog |
| GET | `/api/community/cash_withdrawal` | 团长佣金提现列表 | Action\CommunityChiefCashWithdrawal@getCashWithdrawalList | jwt | api.auth,activated,shoplog |
| POST | `/api/community/cash_withdrawal/{cash_withdrawal_id}` | 处理团长佣金提现申请 | Action\CommunityChiefCashWithdrawal@processCashWithdrawal | jwt | api.auth,activated,shoplog |
| GET | `/api/community/cash_withdrawal/payinfo/{cash_withdrawal_id}` | 获取佣金提现支付信息 | Action\CommunityChiefCashWithdrawal@getMerchantTradeList | jwt | api.auth,activated,shoplog |
| GET | `/api/community/orders` | 获取订单列表 | Action\CommunityOrder@getOrderList | jwt | datapass |
| GET | `/api/community/orders/export` | 导出团购订单 | Action\CommunityOrder@exportActivityOrderData | jwt | datapass |
| GET | `/api/community/order/{order_id}` | 获取订单详情 | Action\CommunityOrder@getOrderDetail | jwt | datapass |
| GET | `/api/community/list` | 活动列表 | Action\CommunityActivity@getList | jwt | api.auth,activated,shoplog |
| POST | `/api/community/activity/confirm/delivery` | 确认发货 | Action\CommunityActivity@confirmDeliveryStatus | jwt | api.auth,activated,shoplog |
| POST | `/api/community/chief/deliver` | 店铺发货 | Action\CommunityActivity@deliver | jwt | api.auth,activated,shoplog |
| GET | `/api/community/items` | 商品列表 | Action\CommunityItems@getItemsList | jwt | api.auth,activated,shoplog |
| POST | `/api/community/items` | 添加商品 | Action\CommunityItems@createItems | jwt | api.auth,activated,shoplog |
| POST | `/api/community/itemMinDeliveryNum` | 修改商品起送量 | Action\CommunityItems@updateItemMinDeliveryNum | jwt | api.auth,activated,shoplog |
| POST | `/api/community/itemSort` | 修改商品排序编号 | Action\CommunityItems@updateItemSort | jwt | api.auth,activated,shoplog |
| DELETE | `/api/community/item/{goods_id}` | 删除商品 | Action\CommunityItems@deleteItem | jwt | api.auth,activated,shoplog |
| GET | `/api/community/activity/setting` | 商品列表 | Action\CommunitySetting@get | jwt | api.auth,activated,shoplog |
| POST | `/api/community/activity/setting` | 添加商品 | Action\CommunitySetting@save | jwt | api.auth,activated,shoplog |

### `routes/api/companys.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| GET | `/api/operator/credential` | 登陆获取证书 | Action\Operators@getCredentials |  | token |
| GET | `/api/operator/basic` | 获取账号基本信息 | Action\Operators@getBasicUserById |  | token |
| GET | `/api/operator/images/code` | 获取图片验证码 | Action\Operators@getImageVcode |  |  |
| POST | `/api/operator/sms/code` | 获取手机短信验证码 | Action\Operators@getSmsCode |  |  |
| POST | `/api/operator/resetpassword` | 重置密码 | Action\Operators@resetPassword |  |  |
| GET | `/api/operator/app/image/code` | 获取图片验证码 | Action\Operators@getAppImageVcode |  |  |
| POST | `/api/operator/app/sms/code` | 发送手机短信验证码 | Action\Operators@getAppSmsCode |  |  |
| POST | `/api/company/activate` | 系统激活 | Action\Companys@active | jwt | api.auth,shoplog |
| GET | `/api/company/activate` | 获取激活信息 | Action\Companys@getActivateInfo | jwt | api.auth,shoplog |
| GET | `/api/company/applications` | 获取授权应用 | Action\Companys@getApplications | jwt | api.auth,shoplog |
| GET | `/api/company/usercenter/authorizeurl` | 用户中心授权连接 | Action\Companys@getUsercenterOuthorizeurl | jwt | api.auth,shoplog |
| POST | `/api/company/usercenter/login` | 用户中心登录 | Action\Companys@loginUsercenter | jwt | api.auth,shoplog |
| GET | `/api/company/appcenter/goods` | 获取应用中心商品 | Action\Companys@getAppcenterGoods | jwt | api.auth,shoplog |
| GET | `/api/company/appcenter/url` | 获取应用中心URL地址 | Action\Companys@getAppcenterUrl | jwt | api.auth,shoplog |
| POST | `/api/company/appcenter/bind/{company_id}` | 绑定应用中心 | Action\Companys@bindAppcenter |  |  |
| GET | `/api/company/resources` | 获取当前可用资源包列表 | Action\Companys@getResourceList | jwt | api.auth,activated,shoplog |
| GET | `/api/companys/setting` | 获取商品配置信息 | Action\Companys@getCompanySetting | jwt | api.auth,activated,shoplog |
| POST | `/api/company/privacy_setting` | 隐私更新 | Action\Companys@setPrivacySetting | jwt | api.auth,activated,shoplog |
| GET | `/api/company/privacy_setting` | 隐私获取 | Action\Companys@getPrivacySetting | jwt | api.auth,activated,shoplog |
| GET | `/api/company/domain_setting` | 获取域名配置信息 | Action\Companys@getDomainSetting | jwt | api.auth,activated,shoplog |
| POST | `/api/company/domain_setting` | 保存域名配置信息 | Action\Companys@setDomainSetting | jwt | api.auth,activated,shoplog |
| POST | `/api/company/setting` | 设置当前企业的基础设置 | Action\Setting@setSetting | jwt | api.auth,activated,shoplog |
| GET | `/api/company/setting` | 获取当前企业的基础设置 | Action\Setting@getSetting | jwt | api.auth,activated,shoplog |
| POST | `/api/share/setting` | 设置分享设置 | Action\Setting@setShareSetting | jwt | api.auth,activated,shoplog |
| GET | `/api/share/setting` | 获取分享设置 | Action\Setting@getShareSetting | jwt | api.auth,activated,shoplog |
| POST | `/api/setting/selfdelivery` | 配置固定的自提地址 | Action\Setting@setSelfdeliveryAddress | jwt | api.auth,activated,shoplog |
| GET | `/api/setting/selfdelivery` | 配置固定的自提地址 | Action\Setting@getSelfdeliveryAddress | jwt | api.auth,activated,shoplog |
| GET | `/api/company/operatorlogs` | 操作日志 | Action\Operators@getCompanysLogs | jwt | api.auth,activated,shoplog |
| PUT | `/api/operator/updatedata` | 更改用户名和头像 | Action\Operators@updateUserData | jwt | api.auth,activated,shoplog |
| POST | `/api/operator/select/distributor` | 店铺端选择店铺 | Action\Operators@shopLoginSelectShopId | jwt | api.auth,activated,shoplog |
| PUT | `/api/operator/changestatus` | 修改账号状态 | Action\Operators@changeOperatorStatus | jwt | api.auth,activated,shoplog |
| POST | `/api/setting/weburl` | 配置外部链接 | Action\Setting@saveWebUrlSetting | jwt | api.auth,activated,shoplog |
| GET | `/api/setting/weburl` | 获取配置外部链接 | Action\Setting@getWebUrlSetting | jwt | api.auth,activated,shoplog |
| GET | `/api/traderate/setting` | 设置评价状态 | Action\Setting@rateSetting | jwt | activated |
| POST | `/api/traderate/setting` | 设置评价状态 | Action\Setting@rateSetting | jwt | activated |
| GET | `/api/member/whitelist/setting` | 获取白名单设置状态 | Action\Setting@whitelistSetting | jwt | activated |
| POST | `/api/member/whitelist/setting` | 设置白名单状态 | Action\Setting@whitelistSetting | jwt | activated |
| GET | `/api/pickupcode/setting` | 设置预售提货码状态 | Action\Setting@pickupcodeSetting | jwt | activated |
| POST | `/api/pickupcode/setting` | 设置预售提货码状态 | Action\Setting@pickupcodeSetting | jwt | activated |
| POST | `/api/ydleads/create` | 云店留资创建 | Action\Operators@createYdleads | jwt | activated |
| GET | `/api/gift/setting` | 赠品相关设置 | Action\Setting@getGiftSetting | jwt | activated |
| POST | `/api/gift/setting` | 赠品相关设置 | Action\Setting@setGiftSetting | jwt | activated |
| GET | `/api/sendoms/setting` | 推oms相关设置 | Action\Setting@getSendOmsSetting | jwt | activated |
| POST | `/api/sendoms/setting` | 推oms相关设置 | Action\Setting@setSendOmsSetting | jwt | activated |
| GET | `/api/nostores/setting` | 获取前端店铺展示开关 | Action\Setting@getNostoresSetting | jwt | activated |
| POST | `/api/nostores/setting` | 设置前端店铺展示开关 | Action\Setting@setNostoresSetting | jwt | activated |
| GET | `/api/recharge/setting` | 设置储值功能状态 | Action\Setting@rechargeSetting | jwt | activated |
| POST | `/api/recharge/setting` | 设置储值功能状态 | Action\Setting@rechargeSetting | jwt | activated |
| GET | `/api/itemStore/setting` | 获取商品库存显示状态 | Action\Setting@itemStoreSetting | jwt | activated |
| POST | `/api/itemStore/setting` | 设置商品库存显示状态 | Action\Setting@itemStoreSetting | jwt | activated |
| GET | `/api/itemSales/setting` | 获取商品销量显示状态 | Action\Setting@itemSalesSetting | jwt | activated |
| POST | `/api/itemSales/setting` | 设置商品销量显示状态 | Action\Setting@itemSalesSetting | jwt | activated |
| GET | `/api/invoice/setting` | 获取发票选项显示状态 | Action\Setting@invoiceSetting | jwt | activated |
| POST | `/api/invoice/setting` | 设置发票选项显示状态 | Action\Setting@invoiceSetting | jwt | activated |
| GET | `/api/itemshare/setting` | 获取商品分享设置 | Action\Setting@getItemShareSetting | jwt | activated |
| POST | `/api/itemshare/setting` | 保存商品分享设置 | Action\Setting@saveItemShareSetting | jwt | activated |
| GET | `/api/shareParameters/setting` | 获取小程序分享参数设置 | Action\Setting@getShareParametersSetting | jwt | activated |
| POST | `/api/shareParameters/setting` | 保存小程序分享参数设置 | Action\Setting@saveShareParametersSetting | jwt | activated |
| GET | `/api/dianwu/setting` | 获取店务端设置 | Action\Setting@getDianwuSetting | jwt | activated |
| POST | `/api/dianwu/setting` | 保存店务端设置 | Action\Setting@saveDianwuSetting | jwt | activated |
| GET | `/api/itemPrice/setting` | 获取商品价格显示配置 | Action\Setting@getItemPriceSetting | jwt | activated |
| POST | `/api/itemPrice/setting` | 保存商品价格显示配置 | Action\Setting@saveItemPriceSetting | jwt | activated |
| GET | `/api/categoryPage/setting` | 获取分类页页面风格设置 | Action\Setting@getCategoryPageSetting | jwt | activated |
| POST | `/api/categoryPage/setting` | 保存分类页页面风格设置 | Action\Setting@saveCategoryPageSetting | jwt | activated |
| POST | `/api/pharmaIndustry/setting` | 保存医药行业设置 | Action\Setting@saveMedicineSetting | jwt | activated |
| GET | `/api/settings` | 获取全部配置配置 | Action\Setting@getAllSetting | jwt | activated |
| POST | `/api/openDivided/setting` | 保存店铺隔离白名单 | Action\Setting@openDistributorDividedSetting | jwt | activated |
| GET | `/api/items/startNum/setting` | 设置商品起订量 | Action\Setting@itemStartNumSetting | jwt | activated |
| POST | `/api/items/startNum/setting` | 设置商品起订量 | Action\Setting@itemStartNumSetting | jwt | activated |
| GET | `/api/supplierItems/startNum/setting` | 设置供应商商品起订量 | Action\Setting@supplirItemStartNumSetting | jwt | activated |
| POST | `/api/supplierItems/startNum/setting` | 设置供应商商品起订量 | Action\Setting@supplirItemStartNumSetting | jwt | activated |
| GET | `/api/mail/setting` | 获取邮件配置 | Action\Setting@getMailSetting | jwt | activated |
| POST | `/api/mail/setting` | 保存邮件配置 | Action\Setting@saveMailSetting | jwt | activated |
| GET | `/api/kujiale/config` | 获取酷家乐路由配置 | Action\Setting@getKujialeConfig | jwt | activated |
| POST | `/api/kujiale/config` | 保存酷家乐路由配置 | Action\Setting@saveKujialeConfig | jwt | activated |
| POST | `/api/shops/wxshops` | 添加微信门店 | Action\Shops@createWxShops | jwt | api.auth,activated,shoplog |
| GET | `/api/shops/wxshops/sync` | 同步微信门店到本地 | Action\Shops@syncWxShops | jwt | api.auth,activated,shoplog |
| GET | `/api/shops/wxshops/setting` | 配置门店通用配置信息 | Action\Shops@getWxShopsSetting | jwt | api.auth,activated,shoplog |
| PUT | `/api/shops/wxshops/setting` | 获取门店通用配置信息 | Action\Shops@setWxShopsSetting | jwt | api.auth,activated,shoplog |
| GET | `/api/shops/wxshops` | 获取微信门店列表 | Action\Shops@getWxShopsList | jwt | api.auth,activated,shoplog |
| POST | `/api/shops/wxshops/setDefaultShop` | 设置默认门店 | Action\Shops@setDefaultShop | jwt | api.auth,activated,shoplog |
| POST | `/api/shops/wxshops/setShopResource` | 激活门店 | Action\Shops@setResource | jwt | api.auth,activated,shoplog |
| GET | `/api/shops/wxshops/{wx_shop_id}` | 获取单个微信门店详情 | Action\Shops@getWxShopsDetail | jwt | api.auth,activated,shoplog |
| DELETE | `/api/shops/wxshops/{wx_shop_id}` | 删除微信门店 | Action\Shops@deleteWxShops | jwt | api.auth,activated,shoplog |
| PUT | `/api/shops/wxshops/{wx_shop_id}` | 更新微信门店 | Action\Shops@updateWxShops | jwt | api.auth,activated,shoplog |
| POST | `/api/shops/wxshops/setShopStatus` | 设置门店状态 | Action\Shops@setShopStatus | jwt | api.auth,activated,shoplog |
| PATCH | `/api/company` | 更新企业信息 | Action\Companys@updateCompanyInfo | jwt | api.auth,activated,shoplog |
| PUT | `/api/shops/protocol` | 更新协议信息 | Action\ProtocolController@set | jwt | api.auth,activated,shoplog |
| GET | `/api/shops/protocol` | 获取协议信息 | Action\ProtocolController@get | jwt | api.auth,activated,shoplog |
| POST | `/api/article/management` | 创建文章 | Action\ArticleController@createDataArticle | jwt | api.auth,activated,shoplog |
| PUT | `/api/article/management/{article_id}` | 更新文章 | Action\ArticleController@updateDataArticle | jwt | api.auth,activated,shoplog |
| DELETE | `/api/article/management/{article_id}` | 删除文章 | Action\ArticleController@deleteDataArticle | jwt | api.auth,activated,shoplog |
| GET | `/api/article/management` | 获取文章列表 | Action\ArticleController@listDataArticle | jwt | api.auth,activated,shoplog |
| GET | `/api/article/management/{article_id}` | 获取文章详情 | Action\ArticleController@infoDataArticle | jwt | api.auth,activated,shoplog |
| PUT | `/api/article/updatestatusorsort` | 获取文章详情 | Action\ArticleController@updateArticleStatusOrSort | jwt | api.auth,activated,shoplog |
| POST | `/api/article/category` | 创建文章栏目 | Action\ArticleCategory@createData | jwt | api.auth,activated,shoplog |
| GET | `/api/article/category` | 获取文章栏目列表 | Action\ArticleCategory@getCategory | jwt | api.auth,activated,shoplog |
| GET | `/api/article/category/{category_id}` | 获取单条文章栏目 | Action\ArticleCategory@getCategory | jwt | api.auth,activated,shoplog |
| PUT | `/api/article/category/{category_id}` | 更新单条文章栏目 | Action\ArticleCategory@updateCategory | jwt | api.auth,activated,shoplog |
| DELETE | `/api/article/category/{category_id}` | 删除文章栏目 | Action\ArticleCategory@deleteCategory | jwt | api.auth,activated,shoplog |
| POST | `/api/currency` | 货币信息新增 | Action\CurrencyController@createData | jwt | api.auth,activated,shoplog |
| DELETE | `/api/currency/{id}` | 删除企业员工 | Action\CurrencyController@deleteData | jwt | api.auth,activated,shoplog |
| PUT | `/api/currency/{id}` | 更新企业员工 | Action\CurrencyController@updateData | jwt | api.auth,activated,shoplog |
| GET | `/api/currency/{id}` | 获取货币详情 | Action\CurrencyController@getDataInfo | jwt | api.auth,activated,shoplog |
| GET | `/api/currency` | 获取货币列表信息 | Action\CurrencyController@getDataList | jwt | api.auth,activated,shoplog |
| PUT | `/api/currencySetDefault/{id}` | 设置默认货币 | Action\CurrencyController@setDefaultCurrency | jwt | api.auth,activated,shoplog |
| GET | `/api/currencyGetDefault` | 获取默认货币配置 | Action\CurrencyController@getDefaultCurrency | jwt | api.auth,activated,shoplog |
| POST | `/api/account/management` | 创建企业员工 | Action\EmployeeController@createData | jwt | api.auth,activated,shoplog |
| GET | `/api/account/management` | 获取企业员工信息列表 | Action\EmployeeController@getListData | jwt | datapass |
| GET | `/api/account/management/{operator_id}` | 获取企业员工信息 | Action\EmployeeController@getInfoData | jwt | api.auth,activated,shoplog |
| PATCH | `/api/account/management/{operator_id}` | 更改企业员工信息 | Action\EmployeeController@updateData | jwt | api.auth,activated,shoplog |
| DELETE | `/api/account/management/{operator_id}` | 删除企业员工信息 | Action\EmployeeController@deleteData | jwt | api.auth,activated,shoplog |
| POST | `/api/roles/management` | 创建企业员工角色 | Action\RolesController@createDataRole | jwt | api.auth,activated,shoplog |
| GET | `/api/roles/management` | 获取角色列表 | Action\RolesController@getDataList | jwt | api.auth,activated,shoplog |
| GET | `/api/roles/management/{role_id}` | 获取角色详情 | Action\RolesController@getDataInfo | jwt | api.auth,activated,shoplog |
| PATCH | `/api/roles/management/{role_id}` | 更新企业员工角色 | Action\RolesController@updateDataRole | jwt | api.auth,activated,shoplog |
| DELETE | `/api/roles/management/{role_id}` | 删除角色 | Action\RolesController@deleteDataRole | jwt | api.auth,activated,shoplog |
| GET | `/api/permission` | 获取权限详情 | Action\RolesController@getPermission | jwt | api.auth,shoplog |
| GET | `/api/operator/getinfo` |  | Action\Operators@getUserData | jwt | api.auth,shoplog |
| GET | `/api/getStatistics` | 获取商城订单统计信息 | Action\StatisticsController@getDataList | jwt | api.auth,activated,shoplog |
| GET | `/api/getNoticeStatistics` | 获取商城总量统计(待处理订单数，待处理商品数，进行中的营销活动数) | Action\StatisticsController@getOrderStatusCount | jwt | api.auth,activated,shoplog |
| GET | `/api/regionauth` | 地区权限列表 | Action\Regionauth@getlist | jwt | api.auth,activated,shoplog |
| GET | `/api/regionauth/{id}` | 地区权限详情 | Action\Regionauth@getinfo | jwt | api.auth,activated,shoplog |
| POST | `/api/regionauth` | 地区权限添加 | Action\Regionauth@add | jwt | api.auth,activated,shoplog |
| PUT | `/api/regionauth/{id}` | 地区权限修改 | Action\Regionauth@update | jwt | api.auth,activated,shoplog |
| DELETE | `/api/regionauth/{id}` | 地区权限删除 | Action\Regionauth@del | jwt | api.auth,activated,shoplog |
| PUT | `/api/regionauth/enable/{id}` | 状态操作 | Action\Regionauth@enable | jwt | api.auth,activated,shoplog |
| GET | `/api/wxexternalconfig/list` | 获取外部小程序配置列表 | Action\WxExternalConfigController@getWxExternalConfigList | jwt | api.auth,activated,shoplog |
| POST | `/api/wxexternalconfig/create` | 创建外部小程序配置 | Action\WxExternalConfigController@createWxExternalConfig | jwt | api.auth,activated,shoplog |
| PUT | `/api/wxexternalconfig/update/{wx_external_config_id}` | 更新外部小程序配置 | Action\WxExternalConfigController@updateWxExternalConfig | jwt | api.auth,activated,shoplog |
| DELETE | `/api/wxexternalconfig/{wx_external_config_id}` | 删除外部小程序配置 | Action\WxExternalConfigController@deleteWxExternalConfig | jwt | api.auth,activated,shoplog |
| GET | `/api/wxexternalroutes/list` | 获取外部小程序路径列表 | Action\WxExternalRoutesController@getwxexternalroutesList | jwt | api.auth,activated,shoplog |
| POST | `/api/wxexternalroutes/create` | 创建外部小程序路径 | Action\WxExternalRoutesController@createwxexternalroutes | jwt | api.auth,activated,shoplog |
| PUT | `/api/wxexternalroutes/update/{wx_external_config_id}` | 更新外部小程序路径 | Action\WxExternalRoutesController@updatewxexternalroutes | jwt | api.auth,activated,shoplog |
| DELETE | `/api/wxexternalroutes/{wx_external_config_id}` | 删除外部小程序路径 | Action\WxExternalRoutesController@deleteWxExternalRoutes | jwt | api.auth,activated,shoplog |
| GET | `/api/wxexternalconfigroutes/list` | 获取外部小程序配置路径列表 | Action\WxExternalConfigController@getConfigRoutesList | jwt | api.auth,activated,shoplog |
| POST | `/api/datapass` | 申请查看敏感数据 | Action\Operators@applyDataPass | jwt | api.auth,activated,shoplog |
| PUT | `/api/datapass/apply/{id}` | 敏感数据申请审核 | Action\Operators@approveDataPass | jwt | api.auth,activated,shoplog |
| PUT | `/api/datapass/open/{id}` | 敏感数据申请开启 | Action\Operators@approveDataPass | jwt | api.auth,activated,shoplog |
| PUT | `/api/datapass/close/{id}` | 敏感数据申请关闭 | Action\Operators@approveDataPass | jwt | api.auth,activated,shoplog |
| GET | `/api/datapass` | 查看敏感数据申请列表 | Action\Operators@listDataPass | jwt | api.auth,activated,shoplog |
| GET | `/api/datapass/{id}` | 查看敏感数据申请详情 | Action\Operators@fetchDataPassDetail | jwt | api.auth,activated,shoplog |
| GET | `/api/datapasslog` | 查看敏感数据日志 | Action\Operators@listDataPassLog | jwt | api.auth,activated,shoplog |
| POST | `/api/operator/member/ready` | 门店会员就绪（开单前） | Action\OperatorStoreMemberController@postReady | jwt | api.auth,activated |
| POST | `/api/operator/scancodeAddcart` | 扫条形码加入购物车 | Action\OperatorCartController@scanCodeSales | jwt | api.auth,activated |
| POST | `/api/operator/cartdataadd` | 管理员购物车新增 | Action\OperatorCartController@cartDataAdd | jwt | api.auth,activated |
| POST | `/api/operator/cartfastbuyadd` | 店务立即购买（云仓分桶） | Action\OperatorCartController@cartFastBuyAdd | jwt | api.auth,activated |
| POST | `/api/operator/cartdataupdate` | 管理员购物车更新 | Action\OperatorCartController@updateCartData | jwt | api.auth,activated |
| GET | `/api/operator/cartdatalist` | 获取管理员购物车 | Action\OperatorCartController@getCartDataList | jwt | api.auth,activated |
| DELETE | `/api/operator/cartdatadel` | 管理员购物车删除 | Action\OperatorCartController@delCartData | jwt | api.auth,activated |
| GET | `/api/operator/pending/list` | 管理员挂单列表 | Action\OperatorPendingOrderController@listPendingData | jwt | api.auth,activated |
| POST | `/api/operator/cartdata/pending` | 管理员购物车挂单 | Action\OperatorPendingOrderController@pendingCartData | jwt | api.auth,activated |
| POST | `/api/operator/order/pending` | 管理员待支付订单挂单 | Action\OperatorPendingOrderController@pendingOrderData | jwt | api.auth,activated |
| POST | `/api/operator/pending/fetch` | 管理员取单 | Action\OperatorPendingOrderController@fetchPendingData | jwt | api.auth,activated |
| DELETE | `/api/operator/pending/delete` | 管理员挂单数据删除 | Action\OperatorPendingOrderController@delPendingData | jwt | api.auth,activated |

### `routes/api/crossborder.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| GET | `/api/crossborder/origincountry` | 获取产地国列表 | Action\OriginCountry@getList | jwt | api.auth,activated,shoplog |
| POST | `/api/crossborder/origincountry` | 产地国添加&修改 | Action\OriginCountry@isAdd | jwt | api.auth,activated,shoplog |
| PUT | `/api/crossborder/origincountry/{origincountry_id}` | 产地国修改 | Action\OriginCountry@isUpdate | jwt | api.auth,activated,shoplog |
| DELETE | `/api/crossborder/origincountry/{origincountry_id}` | 产地国删除 | Action\OriginCountry@isDel | jwt | api.auth,activated,shoplog |
| GET | `/api/crossborder/set` | 获取设置信息 | Action\CrossBorderSet@getInfo | jwt | api.auth,activated,shoplog |
| POST | `/api/crossborder/set` | 保存设置信息 | Action\CrossBorderSet@Save | jwt | api.auth,activated,shoplog |
| GET | `/api/crossborder/taxstrategy` | 税费策略列表 | Action\Taxstrategy@getList | jwt | api.auth,activated,shoplog |
| GET | `/api/crossborder/taxstrategy/{taxstrategy_id}` | 税费策略详情 | Action\Taxstrategy@getInfo | jwt | api.auth,activated,shoplog |
| POST | `/api/crossborder/taxstrategy` | 税费策略添加 | Action\Taxstrategy@isAdd | jwt | api.auth,activated,shoplog |
| PUT | `/api/crossborder/taxstrategy/{taxstrategy_id}` | 税费策略修改 | Action\Taxstrategy@isUpdate | jwt | api.auth,activated,shoplog |
| DELETE | `/api/crossborder/taxstrategy/{taxstrategy_id}` | 税费策略删除 | Action\Taxstrategy@isDel | jwt | api.auth,activated,shoplog |

### `routes/api/dataAnalysis.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| POST | `/api/dataAnalysis/youshu/setting` | 腾讯有数参数设置 | Action\Setting@save | jwt | api.auth,activated,shoplog |
| GET | `/api/dataAnalysis/youshu/query` | 腾讯有数参数查询 | Action\Setting@query | jwt | api.auth,activated,shoplog |

### `routes/api/datacube.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| POST | `/api/datacube/sources` | 添加来源 | Action\Sources@createSources | jwt | api.auth,activated,shoplog |
| GET | `/api/datacube/sources` | 获取来源列表 | Action\Sources@getSourcesList | jwt | api.auth,activated,shoplog |
| GET | `/api/datacube/sources/{source_id}` | 获取来源详情 | Action\Sources@getSourcesDetail | jwt | api.auth,activated,shoplog |
| DELETE | `/api/datacube/sources/{source_id}` | 删除来源 | Action\Sources@deleteSources | jwt | api.auth,activated,shoplog |
| PUT | `/api/datacube/sources/{source_id}` | 更新来源 | Action\Sources@updateSources | jwt | api.auth,activated,shoplog |
| GET | `/api/datacube/companydata` | 获取商城统计数据 | Action\CompanyData@getCompanyData | jwt | api.auth,activated,shoplog |
| GET | `/api/datacube/distributordata` | 获取商城门店统计数据 | Action\DistributorData@getDistributorData | jwt | api.auth,activated,shoplog |
| GET | `/api/datacube/goodsdata` | 获取商品统计数据 | Action\GoodsData@getGoodsData | jwt | api.auth,activated,shoplog |
| POST | `/api/datacube/savetags` | 来源绑定会员标签 | Action\Sources@saveSourceTags | jwt | api.auth,activated,shoplog |
| GET | `/api/datacube/deliverystaffdata` | 获取配送员统计列表 | Action\DeliveryStaffData@getDeliveryStaffData | jwt | api.auth,activated,shoplog |
| GET | `/api/datacube/Deliverystaffdata/export` | 获取配送员统计导出 | Action\DeliveryStaffData@exportDeliverystaffdata | jwt | api.auth,activated,shoplog |
| GET | `/api/datacube/monitors` | 获取页面监控列表 | Action\SourcesMonitors@getSourcesMonitors | jwt | api.auth,activated,shoplog |
| GET | `/api/datacube/monitors/{monitor_id}` | 获取监控详情 | Action\SourcesMonitors@getMonitorsDetail | jwt | api.auth,activated,shoplog |
| DELETE | `/api/datacube/monitors/{monitor_id}` | 删除监控页面 | Action\SourcesMonitors@deleteMonitors | jwt | api.auth,activated,shoplog |
| POST | `/api/datacube/monitors` | 添加监控链接 | Action\SourcesMonitors@addMonitors | jwt | api.auth,activated,shoplog |
| POST | `/api/datacube/monitorsRelSources` | 监控页面关联来源 | Action\SourcesMonitors@relSources | jwt | api.auth,activated,shoplog |
| GET | `/api/datacube/monitorsRelSources/{monitor_id}` | 获取监控页面关联来源信息 | Action\SourcesMonitors@getRelSources | jwt | api.auth,activated,shoplog |
| DELETE | `/api/datacube/monitorsRelSources/{monitor_id}/{source_id}` | 删除监控页面的某个来源 | Action\SourcesMonitors@deleteRelSources | jwt | api.auth,activated,shoplog |
| GET | `/api/datacube/monitorsstats` | 获取监控页面的来源统计 | Action\SourcesMonitors@getStats | jwt | api.auth,activated,shoplog |
| GET | `/api/datacube/monitorsWxaCode64` | 获取监控的小程序码参数 | Action\SourcesMonitors@getMonitorWxaCode64 |  |  |
| GET | `/api/datacube/monitorsWxaCodeStream` | 获取监控的小程序码流信息 | Action\SourcesMonitors@getMonitorWxaCodeStream |  |  |
| GET | `/api/datacube/miniprogram/pages` | 获取小程序的页面及参数信息 | Action\MiniProgram@getPages | jwt | api.auth,activated,shoplog |

### `routes/api/deposit.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| POST | `/api/deposit/rechargerule` | 创建充值面额规则 | Action\Recharge@createRechargeRule | jwt | activated |
| GET | `/api/deposit/rechargerules` | 获取充值面额规则 | Action\Recharge@getRechargeRuleList | jwt | activated |
| DELETE | `/api/deposit/rechargerule/{id}` | 根据ID删除充值面额规则 | Action\Recharge@deleteRechargeRuleById | jwt | activated |
| PUT | `/api/deposit/rechargerule` | 编辑指定充值面额规则 | Action\Recharge@editRechargeRuleById | jwt | activated |
| POST | `/api/deposit/recharge/agreement` | 设置储值协议 | Action\Recharge@setRechargeAgreement | jwt | activated |
| GET | `/api/deposit/recharge/agreement` | 获取储值协议 | Action\Recharge@getRechargeAgreementByCompanyId | jwt | activated |
| POST | `/api/deposit/recharge/multiple` | 设置充值送积分 | Action\Recharge@setRechargeMultiple | jwt | activated |
| GET | `/api/deposit/recharge/multiple` | 获取充值送积分信息 | Action\Recharge@getRechargeMultipleByCompanyId | jwt | activated |
| GET | `/api/deposit/trades` | 获取储值交易记录 | Action\Recharge@getDepositTradeList | jwt | activated,datapass |
| GET | `/api/deposit/count/index` | 获取储值统计页数据 | Action\Recharge@getDepositCountIndex | jwt | activated |
| POST | `/api/deposit/recharge` | 后台充值 | Action\Recharge@recharge | jwt | activated |

### `routes/api/distributor.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| POST | `/api/distributor` | 创建店铺 | Action\Distributor@createDistributor | jwt | activated |
| GET | `/api/distributors` | 获取店铺列表 | Action\Distributor@getDistributorList | jwt | activated,datapass |
| GET | `/api/distributors/info` | 获取指定店铺信息 | Action\Distributor@getDistributorInfo | jwt | activated,datapass |
| PUT | `/api/distributor/{distributor_id}` | 更新店铺 | Action\Distributor@updateDistributor | jwt | activated |
| PUT | `/api/distributor/{distributor_id}/payment-subject` | 设置店铺收款主体 | Action\Distributor@setPaymentSubject | jwt | activated |
| GET | `/api/distributor/count/{distributorId}` | 获取店铺统计 | Action\Distributor@getDistributorCount | jwt | activated |
| GET | `/api/distributor/wxacode` | 获取店铺小程序码 | Action\Distributor@getWxaDistributorCodeStream | jwt | activated |
| POST | `/api/distributor/list/background` | 保存门店列表背景图 | Action\Distributor@saveDistributorListBackground | jwt | activated |
| GET | `/api/distributor/list/background` | 获取门店列表背景图 | Action\Distributor@getDistributorListBackground | jwt | activated |
| GET | `/api/distributor/easylist` | 获取店铺简易列表 | Action\Distributor@getEasyList | jwt | activated |
| POST | `/api/distributor/items` | 添加店铺关联店铺商品 | Action\Distributor@saveDistributorItems | jwt | activated |
| GET | `/api/distributor/items` | 获取店铺关联商品列表 | Action\Distributor@getDistributorItems | jwt | activated |
| GET | `/api/distributor/items/export` | 导出店铺关联商品列表 | Action\Distributor@exportDistributorItems | jwt | activated |
| DELETE | `/api/distributor/items` | 删除店铺关联商品列 | Action\Distributor@delDistributorItems | jwt | activated |
| PUT | `/api/distributors/item` | 配置店铺价格或库存 | Action\Distributor@updateDistributorItem | jwt | activated |
| POST | `/api/distribution/basic_config` | 保存分销基础配置 | Action\BasicConfig@saveBasicConfig | jwt | activated |
| GET | `/api/distribution/basic_config` | 获取分销基础配置 | Action\BasicConfig@getBasicConfig | jwt | activated |
| GET | `/api/distribution/cash_withdrawals` | 获取佣金提现列表 | Action\CashWithdrawal@getCashWithdrawalList | jwt | activated |
| PUT | `/api/distribution/cash_withdrawal/{id}` | 处理佣金提现申请 | Action\CashWithdrawal@processCashWithdrawal | jwt | activated |
| GET | `/api/distributor/cash_withdrawal/payinfo/{cash_withdrawal_id}` | 获取佣金提现支付信息 | Action\CashWithdrawal@getMerchantTradeList | jwt | activated |
| GET | `/api/distribution/logs` | 获取佣金记录 | Action\DistributeLogs@getDistributeLogs | jwt | activated |
| GET | `/api/distribution/count` | 获取分销统计 | Action\DistributeLogs@getCompanyCount | jwt | activated |
| GET | `/api/distributor/getShop` | 获取有效的门店列表 | Action\Distributor@getValidShopList | jwt | activated |
| POST | `/api/distributor/default` | 设置默认门店 | Action\Distributor@defaultSetDistributor | jwt | activated |
| POST | `/api/distributor/tag` | 添加店铺标签 | Action\DistributorTags@createTags | jwt | activated |
| DELETE | `/api/distributor/tag/{tagId}` | 删除店铺标签 | Action\DistributorTags@deleteTag | jwt | activated |
| PUT | `/api/distributor/tag/{tagId}` | 更新店铺标签 | Action\DistributorTags@updateTags | jwt | activated |
| GET | `/api/distributor/tag` | 获取店铺标签列表 | Action\DistributorTags@getTagsList | jwt | activated |
| GET | `/api/distributor/tag/{tagId}` | 获取店铺标签详情 | Action\DistributorTags@getTagsInfo | jwt | activated |
| POST | `/api/distributor/reltag` | 店铺关联标签 | Action\DistributorTags@tagsRelDistributor | jwt | activated |
| POST | `/api/distributor/deltag` | 店铺与店铺标签做解绑 | Action\DistributorTags@tagsRemoveDistributor | jwt | activated |
| POST | `/api/distributor/category` | 新增店铺分类 | Action\DistributorCategory@createCategory | jwt | activated |
| PUT | `/api/distributor/category/{categoryId}` | 更新店铺分类 | Action\DistributorCategory@updateCategory | jwt | activated |
| DELETE | `/api/distributor/category/{categoryId}` | 删除店铺分类 | Action\DistributorCategory@deleteCategory | jwt | activated |
| GET | `/api/distributor/category` | 获取店铺分类列表 | Action\DistributorCategory@getCategoryList | jwt | activated |
| GET | `/api/distributor/category/{categoryId}` | 获取店铺分类详情 | Action\DistributorCategory@getCategoryInfo | jwt | activated |
| POST | `/api/shops` | 创建店铺门店 | Action\DistributorShopController@createShops | jwt | activated |
| PUT | `/api/shops/{distributor_id:[0-9]+}` | 更新店铺门店 | Action\DistributorShopController@updateShops | jwt | activated |
| DELETE | `/api/shops/{distributor_id:[0-9]+}` | 删除店铺门店 | Action\DistributorShopController@deleteShops | jwt | activated |
| GET | `/api/shops/{distributor_id:[0-9]+}` | 获取店铺门店详情 | Action\DistributorShopController@getShopsDetail | jwt | activated |
| GET | `/api/shops` | 获取店铺简易列表 | Action\DistributorShopController@getShopsList | jwt | activated |
| POST | `/api/dshops/setDefaultShop` | 设置默认店铺门店 | Action\DistributorShopController@setDefaultShop | jwt | activated |
| POST | `/api/dshops/setShopStatus` | 设置店铺门店状态 | Action\DistributorShopController@setShopStatus | jwt | activated |
| GET | `/api/distribution/config` | 获取分润配置 | Action\DistributionConfig@getConfig | jwt | activated |
| POST | `/api/distribution/config` | 保存分润配置 | Action\DistributionConfig@setConfig | jwt | activated |
| POST | `/api/distributors/aftersalesaddress` | 添加店铺售后地址 | Action\DistributorAftersalesAddress@setDistributorAfterSalesAddress | jwt | api.auth,shoplog |
| PUT | `/api/distributors/aftersalesaddress` | 修改店铺售后地址 | Action\DistributorAftersalesAddress@setDistributorAfterSalesAddress | jwt | api.auth,shoplog |
| DELETE | `/api/distributors/aftersalesaddress/{address_id}` | 删除店铺售后地址 | Action\DistributorAftersalesAddress@deleteDistributorAfterSalesAddress | jwt | api.auth,shoplog |
| GET | `/api/distributors/aftersalesaddress` | 获取店铺售后地址列表 | Action\DistributorAftersalesAddress@getDistributorAfterSalesAddress | jwt | datapass |
| GET | `/api/distributors/aftersalesaddress/{address_id}` | 获取店铺售后地址详情 | Action\DistributorAftersalesAddress@getDistributorAfterSalesAddressDetail | jwt | api.auth,shoplog |
| GET | `/api/distributors/aftersales` | 获取可退货店铺列表 | Action\Distributor@getOtherOfflineAftersalesDistributor | jwt | activated |
| GET | `/api/distributor/salesperson/role` | 获取门店角色列表 | Action\DistributorSalespersonRole@getRoleList | jwt | activated |
| GET | `/api/distributor/salesperson/role/{salesmanRoleId}` | 获取门店角色 | Action\DistributorSalespersonRole@getRoleInfo | jwt | activated |
| POST | `/api/distributor/salesperson/role` | 保存门店角色 | Action\DistributorSalespersonRole@createRole | jwt | activated |
| PUT | `/api/distributor/salesperson/role/{salesmanRoleId}` | 修改门店角色 | Action\DistributorSalespersonRole@updateRole | jwt | activated |
| DELETE | `/api/distributor/salesperson/role/{salesmanRoleId}` | 删除门店角色 | Action\DistributorSalespersonRole@delRole | jwt | activated |
| GET | `/api/distribution/getdistance` | 获取距离配置 | Action\Distributor@getDistance | jwt | activated |
| POST | `/api/distribution/setdistance` | 保存距离配置 | Action\Distributor@setDistance | jwt | activated |
| POST | `/api/shopScreen/advertisement` | 添加广告 | Action\ShopScreen@addAdvertisement | jwt | api.auth,shoplog |
| DELETE | `/api/shopScreen/advertisement/{id}` | 删除广告 | Action\ShopScreen@deleteAdvertisement | jwt | api.auth,shoplog |
| PUT | `/api/shopScreen/advertisement` | 排序/发布/撤回 | Action\ShopScreen@updateAdvertisement | jwt | api.auth,shoplog |
| GET | `/api/shopScreen/advertisement` | 开屏广告列表 | Action\ShopScreen@getAdvertisement | jwt | api.auth,shoplog |
| POST | `/api/shopScreen/slider` | 轮播图 | Action\ShopScreen@saveSlider | jwt | api.auth,shoplog |
| GET | `/api/shopScreen/slider` | 轮播图 | Action\ShopScreen@getSlider | jwt | api.auth,shoplog |
| GET | `/api/distributor/geofence` | 获取店铺围栏 | Action\DistributorGeofenceController@get | jwt | api.auth,shoplog |
| POST | `/api/distributor/geofence` | 添加或更新一个店铺围栏 | Action\DistributorGeofenceController@save | jwt | api.auth,shoplog |
| DELETE | `/api/distributor/geofence` | 删除一个店铺围栏, 或删除该店铺下的所有围栏 | Action\DistributorGeofenceController@delete | jwt | api.auth,shoplog |
| GET | `/api/pickuplocation/list` | 获取自提点列表 | Action\PickupLocation@getPickupLocationList | jwt | activated,datapass |
| GET | `/api/pickuplocation/{id}` | 获取自提点详情 | Action\PickupLocation@getPickupLocationInfo | jwt | activated,datapass |
| POST | `/api/pickuplocation` | 新增自提点 | Action\PickupLocation@createPickupLocation | jwt | activated,datapass |
| PUT | `/api/pickuplocation/{id}` | 更新自提点 | Action\PickupLocation@updatePickupLocation | jwt | activated,datapass |
| DELETE | `/api/pickuplocation/{id}` | 删除自提点 | Action\PickupLocation@delPickupLocation | jwt | activated,datapass |
| POST | `/api/pickuplocation/reldistributor` | 自提点关联门店 | Action\PickupLocation@relDistributor | jwt | activated,datapass |
| POST | `/api/pickuplocation/reldistributor/cancel` | 自提点取消关联门店 | Action\PickupLocation@cancelRelDistributor | jwt | activated,datapass |
| GET | `/api/distributor/selfdelivery/setting` | 获取商家自配送配置信息 | Action\selfDelivery@getSelfDeliverySetting | jwt | api.auth,shoplog |
| POST | `/api/distributor/selfdelivery/setting` | 商家自配送配置信息保存 | Action\selfDelivery@setSelfDeliverySetting | jwt | api.auth,shoplog |
| GET | `/api/distributor/whitelist/get` | 获取店铺白名单 | Action\DistributorWhiteList@getWhiteList | jwt | api.auth,shoplog |
| POST | `/api/distributor/whitelist/delete` | 删除白名单 | Action\DistributorWhiteList@deleteWhiteList | jwt | api.auth,shoplog |
| POST | `/api/distributor/whitelist/add` | 新增白名单 | Action\DistributorWhiteList@addWhiteList | jwt | api.auth,shoplog |
| GET | `/api/distributor/whitelist/export` | 导出白名单 | Action\DistributorWhiteList@exportWhiteList | jwt | api.auth,shoplog |
| GET | `/api/distributor/config/inRule` | 获取店铺进店规格 | Action\DistributionConfig@getInRule | jwt | api.auth,shoplog |
| POST | `/api/distributor/config/inRule` | 保存店铺进店规格 | Action\DistributionConfig@saveInRule | jwt | api.auth,shoplog |
| GET | `/api/distributor/getAreaByAddress` | 根据地址获取地区信息 | Action\DistributorShopController@getAreaByAddress | jwt | api.auth,shoplog |
| GET | `/api/distributor/salesmans` | 获取店铺导购员列表 | Action\SalespersonController@getSalesmanList | jwt | activated |
| PUT | `/api/distributor/salesman/{salesmanId}` | 更新店铺导购员 | Action\SalespersonController@updateSalesman | jwt | activated |
| GET | `/api/distributor/salesman/role` | 获取店铺导购员权限集合 | Action\SalespersonController@getSalesmanRoleList | jwt | activated |
| PUT | `/api/distributor/salesman/role/{salesmanId}` | 更新店铺导购员权限 | Action\SalespersonController@updateSalesmanRole | jwt | activated |
| POST | `/api/distributor/salesman` | 新增店铺导购员 | Action\SalespersonController@addSalesman | jwt | activated |
| GET | `/api/distributor/salemanCustomerComplaints` | 获取店铺导购员客诉列表 | Action\SalespersonController@getSalemanCustomerComplaintsList | jwt | activated |
| POST | `/api/distributor/salemanCustomerComplaints` | 回复店铺导购员客诉列表 | Action\SalespersonController@replySalemanCustomerComplaints | jwt | activated |
| POST | `/api/salespersonotice/notice` | 添加导购员通知 | Action\SalespersonNotice@addNotice | jwt | activated |
| POST | `/api/salespersonotice/sendnotice` | 发送导购员通知 | Action\SalespersonNotice@sendNotice | jwt | activated |
| POST | `/api/salespersonotice/withdrawnotice` | 撤回导购员通知 | Action\SalespersonNotice@withdrawNotice | jwt | activated |
| GET | `/api/salespersonotice/list` | 导购员通知列表 | Action\SalespersonNotice@getNoticeList | jwt | activated |
| GET | `/api/salespersonotice/detail` | 导购员通知详情 | Action\SalespersonNotice@getNoticeDetail | jwt | activated |
| DELETE | `/api/salespersonotice/notice` | 删除导购员通知 | Action\SalespersonNotice@deleteNotice | jwt | activated |
| PUT | `/api/salespersonotice/notice` | 修改导购员通知 | Action\SalespersonNotice@updateNotice | jwt | activated |
| GET | `/api/profit/statistics` | 获取分润统计 | Action\ProfitController@lists | jwt | activated |
| GET | `/api/profit/export` | 导出分润信息 | Action\ProfitController@exportProfitData | jwt | activated |
| GET | `/api/salesperson/task` | 获取导购任务列表 | Action\SalespersonTaskController@lists | jwt | activated |
| GET | `/api/salesperson/task/statistics` | 获取导购任务统计列表 | Action\SalespersonTaskController@statistics | jwt | activated |
| GET | `/api/salesperson/task/{taskId}` | 获取导购任务详情 | Action\SalespersonTaskController@info | jwt | activated |
| POST | `/api/salesperson/task` | 创建导购任务 | Action\SalespersonTaskController@create | jwt | activated |
| PUT | `/api/salesperson/task/{taskId}` | 修改导购任务 | Action\SalespersonTaskController@update | jwt | activated |
| DELETE | `/api/salesperson/task/{taskId}` | 取消导购任务 | Action\SalespersonTaskController@cancel | jwt | activated |
| GET | `/api/salesperson/coupon` | 获取导购优惠券列表 | Action\SalespersonCouponController@lists | jwt | activated |
| POST | `/api/salesperson/coupon` | 添加导购可发放优惠券 | Action\SalespersonCouponController@create | jwt | activated |
| DELETE | `/api/salesperson/coupon/{id}` | 删除导购优惠券 | Action\SalespersonCouponController@delete | jwt | activated |
| POST | `/api/shops/salesperson` | 添加门店人员 | Action\Salesperson@createSalesperson | jwt | api.auth,activated,shoplog |
| GET | `/api/shops/salesperson` | 获取所有门店人员列表 | Action\Salesperson@lists | jwt | datapass |
| DELETE | `/api/shops/salesperson/{salespersonId}` | 删除门店人员 | Action\Salesperson@deleteSalesperson | jwt | api.auth,activated,shoplog |
| PUT | `/api/shops/salesperson/{salespersonId}` | 更新门店人员 | Action\Salesperson@updateSalesperson | jwt | api.auth,activated,shoplog |
| GET | `/api/shops/saleperson/shoplist` | 获取管理员管理的门店数据 | Action\Salesperson@getRelShopList | jwt | api.auth,activated,shoplog |
| GET | `/api/shops/saleperson/getinfo` | 获取门店人员详细信息 | Action\Salesperson@getSalespersonInfo | jwt | datapass |
| GET | `/api/shops/saleperson/signlogs` | 获取签到记录 | Action\Salesperson@getSignlogs | jwt | api.auth,activated,shoplog |

### `routes/api/employeepurchase.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| POST | `/api/enterprise` | 添加企业 | Action\Enterprise@create | jwt | activated |
| PUT | `/api/enterprise/{enterpriseId}` | 更新企业 | Action\Enterprise@update | jwt | activated |
| DELETE | `/api/enterprise/{enterpriseId}` | 删企业 | Action\Enterprise@delete | jwt | activated |
| GET | `/api/enterprise` | 获取企业列表 | Action\Enterprise@getEnterprisesList | jwt | activated |
| GET | `/api/enterprise/{enterpriseId}` | 获取企业详情 | Action\Enterprise@getEnterpriseInfo | jwt | activated |
| GET | `/api/enterprise/qrcode/{enterpriseId}` | 获取企业小程序码 | Action\Enterprise@getEnterpriseQrcode | jwt | activated |
| POST | `/api/enterprise/status` | 更新企业状态 | Action\Enterprise@updateStatus | jwt | activated |
| POST | `/api/enterprise/sort` | 更新排序 | Action\Enterprise@setSort | jwt | api.auth,shoplog |
| POST | `/api/enterprise/sendtestemail` | 发送测试邮件 | Action\Enterprise@sendTestemail | jwt | activated |
| GET | `/api/employees` | 获取企业员工列表 | Action\Employee@getList | jwt | activated |
| GET | `/api/employee/{employeeId}` | 获取企业员工信息 | Action\Employee@getInfo | jwt | activated |
| POST | `/api/employee` | 添加企业员工 | Action\Employee@create | jwt | activated |
| PUT | `/api/employee/{employeeId}` | 更新企业员工信息 | Action\Employee@update | jwt | activated |
| DELETE | `/api/employee/{employeeId}` | 删除企业员工信息 | Action\Employee@delete | jwt | activated |
| GET | `/api/employees/export` | 导出企业员工信息 | Action\Employee@exportData | jwt | activated |
| GET | `/api/employeepurchase/activity/items` | 获取活动商品列表 | Action\Activity@getActivityItemList | jwt | activated |
| GET | `/api/employeepurchase/activity/items/export` | 导出活动商品 | Action\Activity@exportActivityItems | jwt | activated |
| POST | `/api/employeepurchase/activity/items` | 添加活动商品 | Action\Activity@addActivityItems | jwt | activated |
| POST | `/api/employeepurchase/activity/specitems` | 选择活动商品规格 | Action\Activity@selectActivitySpecItems | jwt | activated |
| PUT | `/api/employeepurchase/activity/items` | 更新活动商品价格库存等 | Action\Activity@updateActivityItems | jwt | activated |
| DELETE | `/api/employeepurchase/activity/{activityId}/item/{itemId}` | 删除活动商品 | Action\Activity@deleteActivityItems | jwt | activated |
| GET | `/api/employeepurchase/activities` | 获取员工内购活动列表 | Action\Activity@getActivityList | jwt | activated |
| GET | `/api/employeepurchase/activity/users` | 获取员工内购活动亲友列表 | Action\Activity@getActivityUsers | jwt | activated |
| GET | `/api/employeepurchase/activity/{activityId}/enterprise-behavior-stats` | 活动企业行为统计 | Action\Activity@getActivityEnterpriseBehaviorStats | jwt | activated |
| GET | `/api/employeepurchase/activity/{activityId}/enterprise-behavior-stats/download` | 下载活动企业行为统计 | Action\Activity@downloadActivityEnterpriseBehaviorStats | jwt | activated |
| GET | `/api/employeepurchase/activity/{activityId}/download-qrcode` | 下载活动企业小程序码 | Action\Activity@downloadActivityQrcode | jwt | activated |
| GET | `/api/employeepurchase/activity/{activityId}` | 获取员工内购活动详情 | Action\Activity@getActivityInfo | jwt | activated |
| POST | `/api/employeepurchase/passphrase-codes/generate` | 批量生成口令码 | Action\Activity@generatePassphraseCodes | jwt | activated |
| POST | `/api/employeepurchase/activity/{activityId}/passphrase-codes/generate` | 批量生成活动口令码(路径) | Action\Activity@generatePassphraseCodesByActivity | jwt | activated |
| POST | `/api/employeepurchase/activity` | 创建员工内购活动 | Action\Activity@createActivity | jwt | activated |
| PUT | `/api/employeepurchase/activity/{activityId}` | 更新员工内购活动 | Action\Activity@updateActivity | jwt | activated |
| POST | `/api/employeepurchase/activity/if_share_store` | 设置活动是否共享库存 | Action\Activity@seIfShareStore | jwt | activated |
| POST | `/api/employeepurchase/activity/cancel/{activityId}` | 取消内购活动 | Action\Activity@cancelActivity | jwt | activated |
| POST | `/api/employeepurchase/activity/suspend/{activityId}` | 暂停内购活动 | Action\Activity@suspendActivity | jwt | activated |
| POST | `/api/employeepurchase/activity/active/{activityId}` | 重新开始暂停的内购活动 | Action\Activity@activeActivity | jwt | activated |
| POST | `/api/employeepurchase/activity/end/{activityId}` | 结束内购活动 | Action\Activity@endActivity | jwt | activated |
| POST | `/api/employeepurchase/activity/ahead/{activityId}` | 提前开始内购活动 | Action\Activity@aheadActivity | jwt | activated |
| GET | `/api/employeepurchase/store-home-page` | 内购模版列表 | Action\StoreHomePage@getList | jwt | activated |
| POST | `/api/employeepurchase/store-home-page` | 创建内购模版 | Action\StoreHomePage@create | jwt | activated |
| GET | `/api/employeepurchase/store-home-page/{id}` | 内购模版详情 | Action\StoreHomePage@getInfo | jwt | activated |
| PUT | `/api/employeepurchase/store-home-page/{id}` | 更新内购模版 | Action\StoreHomePage@update | jwt | activated |
| DELETE | `/api/employeepurchase/store-home-page/{id}` | 删除内购模版 | Action\StoreHomePage@delete | jwt | activated |

### `routes/api/espier.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| GET | `/api/espier/exportCsvData` | 导出csv文件 | Action\ExportData@exportCsvData | jwt | activated |
| POST | `/api/espier/upload_file` | 上传文件 | Action\UploadFile@handleUploadFile | jwt | activated |
| GET | `/api/espier/upload_files` | 获取上传文件列表 | Action\UploadFile@getUploadLists | jwt | activated |
| GET | `/api/espier/upload_error_file_export/{id}` | 上传文件执行后错误信息 | Action\UploadFile@exportUploadErrorFile | jwt | activated |
| GET | `/api/espier/upload_template` | 获取上传文件模版 | Action\UploadFile@exportUploadTemplate | jwt | activated |
| POST | `/api/espier/image_upload_token` | 获取上传图片token | Action\UploadImages@getPicUploadToken | jwt | activated |
| POST | `/api/espier/oss_upload_token` | 获取云存储上传token | Action\UploadImages@getUploadToken | jwt | activated |
| POST | `/api/espier/video_upload_token` | 获取上传视频token | Action\UploadImages@getVideoUploadToken | jwt | activated |
| POST | `/api/espier/image/cat` | 添加图片分类 | Action\UploadImages@editImageCat | jwt | activated |
| GET | `/api/espier/image/cat/children` | 获取分类的子类 | Action\UploadImages@getCatChildren | jwt | activated |
| GET | `/api/espier/image/cat/{image_cat_id}` | 获取分类详情 | Action\UploadImages@getCatInfo | jwt | activated |
| DELETE | `/api/espier/image/cat/{image_cat_id}` | 删除图片分类 | Action\UploadImages@delImgCat | jwt | activated |
| POST | `/api/espier/oss_upload` | OSS直传文件(图片&视频上传) | Action\UploadImages@ossUpload | jwt | activated |
| POST | `/api/espier/image` | 保存图片 | Action\UploadImages@saveImage | jwt | activated |
| GET | `/api/espier/images` | 获取图片列表 | Action\UploadImages@getImageList | jwt | activated |
| DELETE | `/api/espier/images` | 删除图片 | Action\UploadImages@deleteImage | jwt | activated |
| POST | `/api/espier/image/movecat` | 移动图片到指定分类 | Action\UploadImages@moveImageCat | jwt | activated |
| POST | `/api/espier/upload_localimage` | 上传图片 | Action\UploadImages@uploadeImage | jwt | activated |
| GET | `/api/espier/exportlog/list` | 获取文件导出列表 | Action\ExportLogController@getExportLogList | jwt | activated |
| GET | `/api/espier/exportlog/file/down` | 文件导出下载 | Action\ExportLogController@fileDown | jwt | activated |
| GET | `/api/espier/address` | 获取地址 | Action\AddressController@get | jwt | activated |
| GET | `/api/espier/printer` | 获取易联云配置 | Action\PrinterController@info | jwt | activated |
| POST | `/api/espier/printer` | 保存易联云配置 | Action\PrinterController@update | jwt | activated |
| GET | `/api/espier/printer/shop` | 获取商家易联云列表 | Action\PrinterController@getPrinterList | jwt | activated |
| POST | `/api/espier/printer/shop` | 添加商家易联云打印机 | Action\PrinterController@createPrinter | jwt | activated |
| PUT | `/api/espier/printer/shop/{id}` | 更新商家易联云打印机 | Action\PrinterController@updatePrinter | jwt | activated |
| DELETE | `/api/espier/printer/shop/{id}` | 删除商家易联云打印机 | Action\PrinterController@deletePrinter | jwt | activated |
| GET | `/api/espier/config/request_fields` | 获取配置的请求字段列表 | Action\ConfigRequestFieldsController@list | jwt | activated |
| POST | `/api/espier/config/request_fields` | 创建配置的请求字段 | Action\ConfigRequestFieldsController@create | jwt | activated |
| PUT | `/api/espier/config/request_fields/switch` | 更新配置的请求字段的开关 | Action\ConfigRequestFieldsController@updateSwitch | jwt | activated |
| PUT | `/api/espier/config/request_fields/info` | 更新配置的请求字段的内容 | Action\ConfigRequestFieldsController@updateInfo | jwt | activated |
| DELETE | `/api/espier/config/request_fields` | 创建配置的请求字段 | Action\ConfigRequestFieldsController@delete | jwt | activated |
| POST | `/api/espier/config/request_field_setting` | 设置配置请求字段的配置项 | Action\ConfigRequestFieldsController@updateConfig | jwt | activated |
| GET | `/api/espier/config/request_field_setting` | 设置配置请求字段的配置项 | Action\ConfigRequestFieldsController@getConfig | jwt | activated |
| GET | `/api/espier/subdistrict` | 获取街道社区列表 | Action\SubdistrictController@get | jwt | activated |
| GET | `/api/espier/subdistrict/{id}` | 获取街道社区 | Action\SubdistrictController@getInfo | jwt | activated |
| DELETE | `/api/espier/subdistrict/{id}` | 删除街道社区 | Action\SubdistrictController@delete | jwt | activated |
| PUT | `/api/espier/subdistrict` | 保存街道社区 | Action\SubdistrictController@save | jwt | activated |
| GET | `/api/espier/offline/backaccount/lists` | 获取线下收款账户列表 | Action\OfflineBankAccountController@getLists | jwt | activated |
| GET | `/api/espier/offline/backaccount/{id}` | 获取线下收款账户信息 | Action\OfflineBankAccountController@getInfo | jwt | activated |
| DELETE | `/api/espier/offline/backaccount/{id}` | 删除线下收款账户 | Action\OfflineBankAccountController@delete | jwt | activated |
| POST | `/api/espier/offline/backaccount/create` | 创建线下收款账户 | Action\OfflineBankAccountController@create | jwt | activated |
| POST | `/api/espier/offline/backaccount/update` | 更新线下收款账户 | Action\OfflineBankAccountController@update | jwt | activated |
| POST | `/api/espier/system/detect_version` | 升级 | Action\UpgradeController@detectVersion | jwt | api.auth,activated,shoplog |
| POST | `/api/espier/system/upgrade` | 升级 | Action\UpgradeController@upgrade | jwt | api.auth,activated,shoplog |
| POST | `/api/espier/system/rollback` | 回滚 | Action\UpgradeController@rollback | jwt | api.auth,activated,shoplog |
| POST | `/api/espier/system/changelog` | 更新日志 | Action\UpgradeController@changelog | jwt | api.auth,activated,shoplog |
| POST | `/api/espier/system/agreement` | 获取安装协议 | Action\UpgradeController@getAgreement |  | api.throttle |

### `routes/api/fapiao.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| GET | `/api/fapiao/getFapiaoset` | 获取发票配置 | Action\Fapiao@getFapiaoset | jwt | api.auth,shoplog |
| POST | `/api/fapiao/saveFapiaoset` | 保存发票配置 | Action\Fapiao@saveFapiaoset | jwt | api.auth,shoplog |
| GET | `/api/order/invoice/list` | 获取订单发票列表 | Action\Invoice@getInvoiceList | jwt | api.auth,shoplog |
| GET | `/api/order/invoice/info/{id}` | 获取订单发票详情 | Action\Invoice@getInvoiceDetail | jwt | api.auth,shoplog |
| POST | `/api/order/invoice/update/{id}` | 订单发票更新 | Action\Invoice@updateInvoice | jwt | api.auth,shoplog |
| POST | `/api/order/invoice/updateremark/{id}` | 订单发票备注更新 | Action\Invoice@updateInvoiceRemark | jwt | api.auth,shoplog |
| GET | `/api/order/invoice/log/list` | 获取订单发票日志列表 | Action\Invoice@getInvoiceLogList | jwt | api.auth,shoplog |
| POST | `/api/order/invoice/resend` | 重新发送邮件 | Action\Invoice@resendInvoice | jwt | api.auth,shoplog |
| POST | `/api/order/invoice/retryFailedInvoice` | 重新开票 | Action\Invoice@retryFailedInvoice | jwt | api.auth,shoplog |
| GET | `/api/order/invoice/setting` | 获取开票配置 | Action\Invoice@getInvoiceSetting | jwt | api.auth,shoplog |
| POST | `/api/order/invoice/setting` | 设置开票配置 | Action\Invoice@setInvoiceSetting | jwt | api.auth,shoplog |
| POST | `/api/order/invoice/baiwangInvoiceSetting` | 设置百旺发票配置 | Action\Invoice@setBaiwangInvoiceSetting | jwt | api.auth,shoplog |
| GET | `/api/order/invoice/baiwangInvoiceSetting` | 获取百旺发票配置 | Action\Invoice@getBaiwangInvoiceSetting | jwt | api.auth,shoplog |
| POST | `/api/order/invoice/protocol` | 设置发票协议 | Action\Invoice@setInvoiceProtocol | jwt | api.auth,shoplog |
| GET | `/api/order/invoice/protocol` | 获取发票协议 | Action\Invoice@getInvoiceProtocol | jwt | api.auth,shoplog |
| GET | `/api/order/invoice-seller/list` | 获取发票销售方列表 | Action\InvoiceSeller@getSellerList | jwt | api.auth,shoplog |
| GET | `/api/order/invoice-seller/info/{id}` | 获取发票销售方详情 | Action\InvoiceSeller@getSellerDetail | jwt | api.auth,shoplog |
| POST | `/api/order/invoice-seller/create` | 新增发票销售方 | Action\InvoiceSeller@createSeller | jwt | api.auth,shoplog |
| POST | `/api/order/invoice-seller/update/{id}` | 修改发票销售方 | Action\InvoiceSeller@updateSeller | jwt | api.auth,shoplog |
| GET | `/api/order/category-taxrate/list` | 获取分类税率列表 | Action\CategoryTaxRate@getTaxRateList | jwt | api.auth,shoplog |
| GET | `/api/order/category-taxrate/info/{id}` | 获取分类税率详情 | Action\CategoryTaxRate@getTaxRateDetail | jwt | api.auth,shoplog |
| POST | `/api/order/category-taxrate/create` | 新增分类税率 | Action\CategoryTaxRate@createTaxRate | jwt | api.auth,shoplog |
| POST | `/api/order/category-taxrate/update/{id}` | 修改分类税率 | Action\CategoryTaxRate@updateTaxRate | jwt | api.auth,shoplog |
| POST | `/api/order/category-taxrate/delete/{id}` | 删除分类税率 | Action\CategoryTaxRate@deleteTaxRate | jwt | api.auth,shoplog |

### `routes/api/goods.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| POST | `/api/goods/servicelabels` | 添加数值属性 | Action\ServiceLabels@createServiceLabels | jwt | api.auth,activated,shoplog |
| GET | `/api/goods/servicelabels` | 获取数值属性列表 | Action\ServiceLabels@getServiceLabelsList | jwt | api.auth,activated,shoplog |
| GET | `/api/goods/servicelabels/{label_id}` | 获取数值属性详情 | Action\ServiceLabels@getServiceLabelsDetail | jwt | api.auth,activated,shoplog |
| DELETE | `/api/goods/servicelabels/{label_id}` | 删除数值属性 | Action\ServiceLabels@deleteServiceLabels | jwt | api.auth,activated,shoplog |
| PUT | `/api/goods/servicelabels/{label_id}` | 更新数值属性 | Action\ServiceLabels@updateServiceLabels | jwt | api.auth,activated,shoplog |
| POST | `/api/goods/set_commission_ratio` | 设置商品佣金费率 | Action\Items@setCommissionRatio | jwt | api.auth,activated,shoplog |
| PUT | `/api/goods/audit/items` | 商品审核 | Action\Items@auditItems | jwt | api.auth,activated,shoplog |
| POST | `/api/goods/items` | 添加商品 | Action\Items@createItems | jwt | api.auth,activated,shoplog |
| POST | `/api/goods/setItemsTemplate` | 更新商品运费模板 | Action\Items@setItemsTemplate | jwt | api.auth,activated,shoplog |
| POST | `/api/goods/setItemsCategory` | 更新商品分类 | Action\Items@setItemsCategory | jwt | api.auth,activated,shoplog |
| GET | `/api/goods/items` | 获取商品列表 | Action\Items@getItemsList | jwt | api.auth,activated,shoplog |
| GET | `/api/goods/items/onsale` | 获取可销售的商品列表 | Action\Items@getOnsaleItemsList | jwt | api.auth,activated,shoplog |
| POST | `/api/goods/save_group_item` | 保存分组商品 | Action\ItemsGroupController@saveGroupItem | jwt | api.auth,activated,shoplog |
| GET | `/api/goods/get_group_items` | 查询分组商品 | Action\ItemsGroupController@getGroupItems | jwt | api.auth,activated,shoplog |
| GET | `/api/goods/sku` | 获取商品列表 | Action\Items@getSkuList | jwt | api.auth,activated,shoplog |
| GET | `/api/goods/items/{item_id}` | 获取商品详情 | Action\Items@getItemsDetail | jwt | api.auth,activated,shoplog |
| DELETE | `/api/goods/items/{item_id}` | 删除商品 | Action\Items@deleteItems | jwt | api.auth,activated,shoplog |
| DELETE | `/api/goods/items/{item_id}/response` | 删除商品 | Action\Items@deleteItemsResponseData | jwt | api.auth,activated,shoplog |
| PUT | `/api/goods/items/{item_id}` | 更新商品 | Action\Items@updateItems | jwt | api.auth,activated,shoplog |
| GET | `/api/goods/distributionGoodsWxaCodeStream` | 获取商品分销二维码 | Action\Items@getDistributionGoodsWxaCodeStream | jwt | api.auth,activated,shoplog |
| POST | `/api/goods/warning_store` | 设置商品预警库存 | Action\Items@setItemWarningStore | jwt | api.auth,activated,shoplog |
| POST | `/api/goods/setItemsSort` | 更新商品排序 | Action\Items@setItemsSort | jwt | api.auth,activated,shoplog |
| GET | `/api/goods/epidemicItems/list` | 疫情商品配置列表 | Action\EpidemicItems@epidemicItemsList | jwt | api.auth,activated,shoplog |
| GET | `/api/goods/epidemicRegister/list` | 疫情订单登记列表 | Action\EpidemicItems@epidemicRegisterList | jwt | datapass |
| POST | `/api/goods/epidemicRegister/export` | 疫情防控登记导出 | Action\EpidemicItems@exportEpidemicRegisterData | jwt | datapass |
| PUT | `/api/goods/itemstoreupdate` | 设置商品库存 | Action\Items@batchUpdateItemStore | jwt | activated |
| PUT | `/api/goods/itemstatusupdate` | 设置商品状态 | Action\Items@batchUpdateItemStatus | jwt | activated |
| GET | `/api/goods/category` | 获取商品分类列表 | Action\ItemsCategory@getCategory | jwt | api.auth,activated,shoplog |
| GET | `/api/goods/category/{category_id}` | 获取单条分类数据 | Action\ItemsCategory@getCategoryInfo | jwt | api.auth,activated,shoplog |
| GET | `/api/goods/category/saleable-filter/get` | 获取分类可售过滤开关 | Action\ItemsCategory@getSaleableCategoryFilterStatus | jwt | api.auth,activated,shoplog |
| POST | `/api/goods/category` | 添加分类 | Action\ItemsCategory@createCategory | jwt | api.auth,activated,shoplog |
| POST | `/api/goods/category/saleable-filter` | 设置分类可售过滤开关 | Action\ItemsCategory@setSaleableCategoryFilterStatus | jwt | api.auth,activated,shoplog |
| POST | `/api/goods/createcategory` | 添加分类 | Action\ItemsCategory@createClassification | jwt | api.auth,activated,shoplog |
| DELETE | `/api/goods/category/{category_id}` | 删除分类 | Action\ItemsCategory@deleteCategory | jwt | api.auth,activated,shoplog |
| PUT | `/api/goods/category/{category_id}` | 更新单条分类信息 | Action\ItemsCategory@updateCategory | jwt | api.auth,activated,shoplog |
| POST | `/api/goods/attributes` | 新增商品属性 | Action\ItemsAttributes@addItemsAttributes | jwt | api.auth,activated,shoplog |
| PUT | `/api/goods/attributes/{attribute_id}` | 更新商品属性 | Action\ItemsAttributes@updateItemsAttributes | jwt | api.auth,activated,shoplog |
| GET | `/api/goods/attributes` | 获取商品属性列表 | Action\ItemsAttributes@getItemsAttrList | jwt | api.auth,activated,shoplog |
| DELETE | `/api/goods/attributes/{attribute_id}` | 删除商品属性 | Action\ItemsAttributes@deleteItemsAttributes | jwt | api.auth,activated,shoplog |
| PUT | `/api/goods/itemsupdate` | 修改商品价格、库存、上下架状态 | Action\Items@updateItemsPriceStoreStatus | jwt | api.auth,activated,shoplog |
| POST | `/api/goods/memberprice/save` | 保存商品会员价 | Action\MemberPrice@saveMemberPrice | jwt | api.auth,activated,shoplog |
| GET | `/api/goods/memberprice/{item_id}` | 获取会员价列表 | Action\MemberPrice@getMemberPriceList | jwt | api.auth,activated,shoplog |
| POST | `/api/goods/tag` | 添加商品标签 | Action\ItemsTags@createTags | jwt | activated |
| DELETE | `/api/goods/tag/{tag_id}` | 删除商品标签 | Action\ItemsTags@deleteTag | jwt | activated |
| PUT | `/api/goods/tag` | 更新商品标签 | Action\ItemsTags@updateTags | jwt | activated |
| GET | `/api/goods/tag` | 获取商品标签列表 | Action\ItemsTags@getTagsList | jwt | activated |
| GET | `/api/goods/tag/{tag_id}` | 获取商品标签详情 | Action\ItemsTags@getTagsInfo | jwt | activated |
| POST | `/api/goods/reltag` | 商品关联标签 | Action\ItemsTags@tagsRelItem | jwt | activated |
| GET | `/api/goods/tagsearch` | 获取商品关联标签 | Action\ItemsTags@getItemIdsByTagids | jwt | activated |
| POST | `/api/goods/rebateconf` | 保存商品会员价 | Action\Items@updateItemsRebateConf | jwt | activated |
| POST | `/api/goods/exportApiFileName` | 导出商品信息 | Action\ExportItems@exportItemsDataApiReturn | jwt | api.auth,activated,shoplog |
| POST | `/api/goods/export` | 导出商品信息 | Action\ExportItems@exportItemsData | jwt | api.auth,activated,shoplog |
| POST | `/api/goods/tag/export` | 导出商品标签信息 | Action\ExportItems@exportItemsTagData | jwt | api.auth,activated,shoplog |
| POST | `/api/goods/code/export` | 导出商品码 | Action\ExportItems@exportItemsCodeData | jwt | api.auth,activated,shoplog |
| GET | `/api/goods/goodsbycoupon/{coupon_id}` | 根据优惠券获取优惠券可使用的商品 | Action\Items@getGoodsByCoupon | jwt | api.auth,activated,shoplog |
| GET | `/api/goods/profit/{item_id}` | 商品导购分润配置获取 | Action\ItemsProfit@getItemsProfit | jwt | api.auth,activated,shoplog |
| POST | `/api/goods/profit/save` | 商品导购分润配置保存 | Action\ItemsProfit@saveGoodsProfit | jwt | api.auth,activated,shoplog |
| POST | `/api/goods/category/profit/save` | 商品类目导购分润配置保存 | Action\ItemsCategoryProfit@saveItemsCategoryProfit | jwt | api.auth,activated,shoplog |
| POST | `/api/goods/keywords` | 设置关键词 | Action\Items@setKeywords | jwt | api.auth,activated,shoplog |
| DELETE | `/api/goods/keywords/{id}` | 删除关键词 | Action\Items@delKeywords | jwt | api.auth,activated,shoplog |
| GET | `/api/goods/keywords` | 获取关键词 | Action\Items@getKeywords | jwt | api.auth,activated,shoplog |
| GET | `/api/goods/keywordsDetail` | 获取关键词详情 | Action\Items@getKeyWordsDetail | jwt | api.auth,activated,shoplog |
| POST | `/api/goods/sync/items` | 从oms同步商品数据 | Action\SyncFromOme@syncItems | jwt | api.auth,activated,shoplog |
| POST | `/api/goods/sync/itemCategory` | 从oms同步商品分类 | Action\SyncFromOme@syncItemCategory | jwt | api.auth,activated,shoplog |
| POST | `/api/goods/sync/itemSpec` | 从oms同步商品规格 | Action\SyncFromOme@syncItemSpec | jwt | api.auth,activated,shoplog |
| POST | `/api/goods/sync/brand` | 从oms同步品牌 | Action\SyncFromOme@syncBrand | jwt | api.auth,activated,shoplog |
| PUT | `/api/goods/itemsisgiftupdate` | 批量设置商品为赠品 | Action\Items@batchUpdateItemIsgift | jwt | activated |
| GET | `/api/goods/commission/{item_id}` | 商品佣金配置获取 | Action\ItemsCommission@getItemsCommission | jwt | api.auth,activated,shoplog |
| POST | `/api/goods/commission/save` | 商品佣金配置保存 | Action\ItemsCommission@saveGoodsCommission | jwt | api.auth,activated,shoplog |
| POST | `/api/goods/upload/items` | 上传商品信息到聚水潭 | Action\Jushuitan@uploadItems | jwt | api.auth,activated,shoplog |
| POST | `/api/goods/query/inventory` | 同步聚水潭商品库存 | Action\Jushuitan@queryInventory | jwt | api.auth,activated,shoplog |
| POST | `/api/goods/upload/wdterp/items` | 上传商品信息到旺店通 | Action\WdtErp@uploadItems | jwt | api.auth,activated,shoplog |
| POST | `/api/goods/medicineItems/sync` | 重新推送药品数据 | Action\Items@syncMedicine | jwt | api.auth,activated,shoplog |
| POST | `/api/goods/items/params` | 添加商品参数接口 | Action\Items@createItemsParams | jwt | api.auth,activated,shoplog |

### `routes/api/hfpay.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| GET | `/api/hfpay/ledgerconfig/index` | 汇付获取分账配置 | Action\HfpayLedgerConfig@index | jwt | activated |
| POST | `/api/hfpay/ledgerconfig/save` | 保存分账配置 | Action\HfpayLedgerConfig@save | jwt | activated |
| GET | `/api/hfpay/enterapply/apply` | 获取入驻信息 | Action\HfpayEnterapply@apply | jwt | activated |
| POST | `/api/hfpay/enterapply/save` | 保存入驻信息 | Action\HfpayEnterapply@saveEnterapply | jwt | activated |
| GET | `/api/hfpay/enterapply/getList` | 获取店铺入驻列表信息 | Action\HfpayEnterapply@getApplyList | jwt | activated |
| POST | `/api/hfpay/enterapply/hfkaihu` | 企业个体户开户 | Action\HfpayEnterapply@hfKaiHu | jwt | activated |
| POST | `/api/hfpay/enterapply/hffile` | 汇付文件上传 | Action\HfpayEnterapply@hfFile | jwt | activated |
| POST | `/api/hfpay/enterapply/opensplit` | 店铺分账开关 | Action\HfpayEnterapply@openSplit | jwt | activated |
| GET | `/api/hfpay/getwithdrawset` | 汇付获取提现设置 | Action\HfpayWithdrawSet@index | jwt | activated |
| POST | `/api/hfpay/savewithdrawset` | 汇付提现设置 | Action\HfpayWithdrawSet@save | jwt | activated |
| GET | `/api/hfpay/statistics/distributor` | 店铺分账交易统计 | Action\HfpayStatistics@distributor | jwt | activated |
| GET | `/api/hfpay/statistics/company` | 平台分账交易统计 | Action\HfpayStatistics@company | jwt | activated |
| GET | `/api/hfpay/statistics/exportData` | 导出报表 | Action\HfpayStatistics@exportData | jwt | activated |
| GET | `/api/hfpay/statistics/orderList` | 分账统计列表 | Action\HfpayStatistics@orderList | jwt | activated |
| GET | `/api/hfpay/statistics/orderDetail/{orderId}` | 分账统计详情 | Action\HfpayStatistics@orderDetail | jwt | activated |
| GET | `/api/hfpay/statistics/orderExportData` | 分账统计导出报表 | Action\HfpayStatistics@orderExportData | jwt | activated |
| GET | `/api/hfpay/withdraw/getList` | 汇付提现记录 | Action\HfpayCashRecord@getList | jwt | activated |
| POST | `/api/hfpay/withdraw` | 汇付提现 | Action\HfpayCashRecord@withdraw | jwt | activated |
| GET | `/api/hfpay/withdraw/exportData` | 汇付提现记录导出 | Action\HfpayCashRecord@exportData | jwt | activated |

### `routes/api/im.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| GET | `/api/im/meiqia` | 获取im配置 | Action\Im@meiqiaInfo | jwt | api.auth,shoplog |
| POST | `/api/im/meiqia` | 保存im配置 | Action\Im@meiqiaUpdate | jwt | api.auth,shoplog |
| GET | `/api/im/meiqia/distributor/{distributor_id}` | 获取店铺美洽客服配置 | Action\Im@getDistributorMeiQiaSetting | jwt | api.auth,shoplog |
| PUT | `/api/im/meiqia/distributor/{distributor_id}` | 设置店铺美洽客服配置 | Action\Im@setDistributorMeiQia | jwt | api.auth,shoplog |
| GET | `/api/im/echat` | 获取一洽配置 | Action\EChat@getInfo | jwt | api.auth,shoplog |
| POST | `/api/im/echat` | 保存一洽配置 | Action\EChat@saveInfo | jwt | api.auth,shoplog |

### `routes/api/kujiale.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| POST | `/api/kujiale/designer-works/bind-item` | 绑定设计师作品与商品 | Action\KuController@bindDesignerWorksItem | jwt | api.auth,activated,shoplog |
| DELETE | `/api/kujiale/designer-works/unbind-item` | 解绑设计师作品与商品 | Action\KuController@unbindDesignerWorksItem | jwt | api.auth,activated,shoplog |
| GET | `/api/kujiale/designer-works/items` | 查询关联了design的商品列表 | Action\KuController@getDesignerWorksItems | jwt | api.auth,activated,shoplog |
| GET | `/api/kujiale/designer-works/list` | 获取设计师作品列表 | Action\KuController@getDesignerWorksList | jwt | api.auth,activated,shoplog |

### `routes/api/member.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| GET | `/api/wechat/fans/list` | 获取微信用户列表 | Action\WechatFans@getWxFansList | jwt | activated |
| GET | `/api/wechat/fans` | 获取微信用户基本信息 | Action\WechatFans@getWxFansInfo | jwt | activated |
| PUT | `/api/wechat/fans/remark` | 修改微信用户备注 | Action\WechatFans@wxremark | jwt | activated |
| GET | `/api/wechat/fans/sync` | 同步微信用户列表 | Action\WechatFans@syncWechatFans | jwt | activated |
| GET | `/api/wechat/fans/tags` | 获取指定用户的标签列表 | Action\WechatFans@getWxTagsOfUser | jwt | activated |
| POST | `/api/wechat/tag` | 微信标签创建 | Action\WechatFansTags@wxtagCreate | jwt | activated |
| PUT | `/api/wechat/tag` | 微信标签更新 | Action\WechatFansTags@wxtagUpdate | jwt | activated |
| DELETE | `/api/wechat/tag` | 删除微信标签 | Action\WechatFansTags@wxtagDelete | jwt | activated |
| GET | `/api/wechat/tags` | 获取微信标签列表 | Action\WechatFansTags@getWxtagList | jwt | activated |
| GET | `/api/wechat/tag/sync` | 同步微信用户标签列表 | Action\WechatFansTags@syncWechatTags | jwt | activated |
| GET | `/api/wechat/tag/fans` | 获取指定标签下用户列表 | Action\WechatFans@getWxFansOfTag | jwt | activated |
| PATCH | `/api/wechat/tag/batchSet` | 微信用户批量打标签 | Action\WechatFansTags@batchSetUserTags | jwt | activated |
| POST | `/api/members/register/setting` | 设置会员注册项 | Action\Members@setMemberRegItems | jwt | activated |
| GET | `/api/members/register/setting` | 获取会员注册项 | Action\Members@getMemberRegItems | jwt | activated |
| GET | `/api/members` | 获取会员列表 | Action\Members@getMemberList | jwt | activated,datapass |
| GET | `/api/member` | 获取会员信息 | Action\Members@getMemberInfo | jwt | activated,datapass |
| GET | `/api/member/sms/code` |  | Action\Members@getSmsCode | jwt | api.auth,shoplog |
| GET | `/api/member/image/code` |  | Action\Members@getImageVcode | jwt | api.auth,shoplog |
| GET | `/api/member/verify` | POS会员手机号验证码校验并查询会员信息 | Action\Members@verifyMember | jwt | activated |
| POST | `/api/member` | 新增会员 | Action\Members@createMember | jwt | activated |
| PATCH | `/api/member` | 更新会员信息 | Action\Members@updateMemberInfo | jwt | activated |
| PUT | `/api/member` | 更新会员手机信息 | Action\Members@updateMobileById | jwt | activated |
| PUT | `/api/member/salesman` | 设置会员的导购员 | Action\Members@setMemberSalesman | jwt | activated |
| PUT | `/api/member/grade` | 更新会员等级 | Action\Members@updateGradeById | jwt | activated |
| PATCH | `/api/member/grade` | 批量更新会员等级 | Action\Members@updateGrade | jwt | activated |
| PUT | `/api/member/regDistributor` | 批量更新会员注册分销商 | Action\Members@reChangeRegDistributor | jwt | activated |
| GET | `/api/operate/loglist` | 获取会员操作日志 | Action\Members@gerMemberOperateLogList | jwt | activated |
| POST | `/api/member/smssend` | 会员群发短信 | Action\SmsController@smsSends | jwt | activated |
| POST | `/api/member/tag` | 新增会员标签 | Action\MemberTags@createTags | jwt | activated |
| DELETE | `/api/member/tag/{tag_id}` | 删除会员标签 | Action\MemberTags@deleteTag | jwt | activated |
| PUT | `/api/member/tag` | 更新会员标签 | Action\MemberTags@updateTags | jwt | activated |
| GET | `/api/member/tag` | 获取会员标签列表 | Action\MemberTags@getTagsList | jwt | activated |
| GET | `/api/member/tag/{tag_id}` | 获取会员标签详情 | Action\MemberTags@getTagsInfo | jwt | activated |
| POST | `/api/member/reltagdel` | 删除会员标签 | Action\MemberTags@tagsRelUserDel | jwt | activated |
| POST | `/api/member/reltag` | 关联会员标签 | Action\MemberTags@tagsRelUser | jwt | activated |
| GET | `/api/member/tagsearch` | 根据标签筛选会员 | Action\MemberTags@getUserIdsByTagids | jwt | activated |
| GET | `/api/member/export` | 导出会员信息 | Action\ExportData@exportMemberData | jwt | activated,datapass |
| POST | `/api/member/batchOperating` | 批量操作会员信息 | Action\ExportData@batchProcessMemberData | jwt | activated |
| POST | `/api/member/tagcategory` | 新增会员标签分类 | Action\TagsCategoryController@createTagsCategory | jwt | activated |
| DELETE | `/api/member/tagcategory/{category_id}` | 删除会员标签分类 | Action\TagsCategoryController@deleteTagsCategory | jwt | activated |
| PUT | `/api/member/tagcategory/{category_id}` | 更新会员标签分类 | Action\TagsCategoryController@updateTagsCategory | jwt | activated |
| GET | `/api/member/tagcategory` | 获取会员标签分类列表 | Action\TagsCategoryController@getTagsCategoryList | jwt | activated |
| GET | `/api/member/tagcategory/{category_id}` | 获取会员标签分类详情 | Action\TagsCategoryController@getTagsCategoryInfo | jwt | activated |
| POST | `/api/member/bindusersalespersonrel` | 添加或修改会员与导购员的绑定关系 | Action\Members@bindUserSalespersonRel | jwt | activated |
| PUT | `/api/member/update` | 更新会员基础信息 | Action\Members@updateMember | jwt | activated |
| GET | `/api/members/whitelist/list` | 获取会员白名单列表 | Action\MembersWhitelist@getLists | jwt | activated,datapass |
| GET | `/api/members/whitelist/{id}` | 获取会员白名单详情 | Action\MembersWhitelist@getInfo | jwt | activated |
| POST | `/api/members/whitelist` | 创建会员白名单 | Action\MembersWhitelist@createData | jwt | api.auth,shoplog |
| POST | `/api/members/whitelist/{id}` | 更改会员白名单信息 | Action\MembersWhitelist@updateData | jwt | api.auth,shoplog |
| DELETE | `/api/members/whitelist/{id}` | 删除会员白名单信息 | Action\MembersWhitelist@deleteData | jwt | api.auth,shoplog |
| GET | `/api/members/subscribe/list` | 获取订阅列表 | Action\MembersSubscribe@getLists | jwt | activated |
| POST | `/api/members/trustlogin/list` | 获取信任登录列表 | Action\TrustLogin@getTrustLoginList | jwt | activated |
| PUT | `/api/members/trustlogin/setting` | 保存信任登录状态 | Action\TrustLogin@saveStatusSetting | jwt | activated |
| POST | `/api/members/trustlogin/setting` | 保存信任登录状态POST | Action\TrustLogin@saveStatusSetting | jwt | activated |
| POST | `/api/member/tag-group` | 新增标签组 | Action\MemberTags@createTagGroup | jwt | activated |
| DELETE | `/api/member/tag-group/{group_id}` | 删除标签组 | Action\MemberTags@deleteTagGroup | jwt | activated |
| GET | `/api/member/tag-group` | 标签组列表 | Action\MemberTags@getTagGroupList | jwt | activated |
| PUT | `/api/member/tag-group/{group_id}` | 编辑标签组 | Action\MemberTags@updateTagGroup | jwt | activated |
| GET | `/api/member/segment-rule/structure` | 获取规则结构配置 | Action\MemberTagAction@getRuleStructure | jwt | activated |
| POST | `/api/member/segment-rule/preview` | 预览人群规则（查询匹配的用户ID） | Action\MemberTagAction@previewSegmentRule | jwt | activated |
| GET | `/api/member/segment-rule` | 获取人群规则列表 | Action\MemberTagAction@getSegmentRuleList | jwt | activated |
| POST | `/api/member/segment-rule` | 创建人群规则 | Action\MemberTagAction@createSegmentRule | jwt | activated |
| GET | `/api/member/segment-rule/{rule_id}` | 查看人群规则详情 | Action\MemberTagAction@getSegmentRule | jwt | activated |
| PUT | `/api/member/segment-rule/{rule_id}` | 编辑人群规则 | Action\MemberTagAction@updateSegmentRule | jwt | activated |

### `routes/api/merchant.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| GET | `/api/merchant/basesetting` | 获取基础设置 | Action\MerchantSetting@getBase | jwt | activated |
| POST | `/api/merchant/basesetting` | 保存基础设置 | Action\MerchantSetting@saveBase | jwt | activated |
| GET | `/api/merchant/type/list` | 获取商户类型列表 | Action\MerchantSetting@getTypeList | jwt | activated |
| POST | `/api/merchant/type/create` | 新增商户类型 | Action\MerchantSetting@createType | jwt | activated |
| PUT | `/api/merchant/type/{id}` | 更新单条商户类型信息 | Action\MerchantSetting@updateType | jwt | api.auth,activated,shoplog |
| DELETE | `/api/merchant/type/{id}` | 删除商户类型 | Action\MerchantSetting@deleteType | jwt | api.auth,activated,shoplog |
| GET | `/api/merchant/operator` | 获取商户账号列表 | Action\MerchantOperator@getOperatorList | jwt | activated,datapass |
| POST | `/api/merchant/operator` | 商户修改密码 | Action\MerchantOperator@updateOperatorAccount | jwt | activated |
| PUT | `/api/merchant/operator/{id}` | 平台重置商户密码 | Action\MerchantOperator@resetOperatorAccount | jwt | activated |
| GET | `/api/merchant/settlement/apply/list` | 获取商户入驻申请列表 | Action\MerchantSettlementApply@getList | jwt | activated |
| GET | `/api/merchant/settlement/apply/{id}` | 获取商户入驻申请详情 | Action\MerchantSettlementApply@getDetail | jwt | activated,datapass |
| POST | `/api/merchant/settlement/apply/audit` | 审核商户入驻申请 | Action\MerchantSettlementApply@auditData | jwt | activated |
| GET | `/api/merchant/list` | 获取商户列表 | Action\Merchant@getList | jwt | activated,datapass |
| GET | `/api/merchant/detail/{id}` | 获取商户详情 | Action\Merchant@getDetail | jwt | activated,datapass |
| POST | `/api/merchant/{id}` | 更新商户 | Action\Merchant@updateMerchant | jwt | activated |
| POST | `/api/merchant/` | 新增商户 | Action\Merchant@createMerchant | jwt | activated |
| GET | `/api/merchant/visibletype/list` | 获取可见商户类型列表 | Action\MerchantSetting@getVisibleTypeList | jwt | activated |
| POST | `/api/merchant/disabled/update/{id}` | 修改单个商户的禁用状态 | Action\Merchant@updateMerchantDisabled | jwt | activated |
| POST | `/api/merchant/auditgoods/update/{id}` | 修改单个商户的审核商品状态 | Action\Merchant@updateMerchantAuditGoods | jwt | activated |
| GET | `/api/merchant/info` | 获取商户详情 | Action\Merchant@getInfo | jwt | activated |
| POST | `/api/supplier/register` | 供应商入驻 | Action\Supplier@register | jwt | api.auth,activated,shoplog |
| GET | `/api/supplier/get_supplier_info` | 查询供应商信息 | Action\Supplier@getSupplierInfo | jwt | api.auth,activated,shoplog |
| GET | `/api/supplier/get_supplier_list` | 查询供应商列表 | Action\Supplier@getSupplierList | jwt | api.auth,activated,shoplog |
| POST | `/api/supplier/check_supplier` | 审核供应商 | Action\Supplier@checkSupplier | jwt | api.auth,activated,shoplog |
| POST | `/api/supplier/batch_review_items` | 批量审核供应商商品 | Action\SupplierItem@batchReviewItems | jwt | api.auth,activated,shoplog |

### `routes/api/notice.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| GET | `/api/notice/list` | 获取公告列表 | Action\ShopNotice@getShopNoticeList | jwt | api.auth,shoplog |
| GET | `/api/notice/{notice_id}` | 获取公告详情 | Action\ShopNotice@getShopNoticeInfo | jwt | api.auth,shoplog |

### `routes/api/onecode.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| POST | `/api/onecode/things` | 添加物品 | Action\Things@createThings | jwt | activated,api.auth,shoplog |
| GET | `/api/onecode/things` | 获取物品列表 | Action\Things@getThingsList | jwt | activated,api.auth,shoplog |
| GET | `/api/onecode/things/{thing_id}` | 获取物品详情 | Action\Things@getThingsDetail | jwt | activated,api.auth,shoplog |
| DELETE | `/api/onecode/things/{thing_id}` | 删除物品 | Action\Things@deleteThings | jwt | activated,api.auth,shoplog |
| PUT | `/api/onecode/things/{thing_id}` | 更新物品 | Action\Things@updateThings | jwt | activated,api.auth,shoplog |
| POST | `/api/onecode/batchs` | 添加物品批次 | Action\Batchs@createBatchs | jwt | activated,api.auth,shoplog |
| GET | `/api/onecode/batchs` | 获取物品批次列表 | Action\Batchs@getBatchsList | jwt | activated,api.auth,shoplog |
| GET | `/api/onecode/batchs/{batch_id}` | 获取物品批次详情 | Action\Batchs@getBatchsDetail | jwt | activated,api.auth,shoplog |
| DELETE | `/api/onecode/batchs/{batch_id}` | 删除物品批次 | Action\Batchs@deleteBatchs | jwt | activated,api.auth,shoplog |
| PUT | `/api/onecode/batchs/{batch_id}` | 更新物品批次 | Action\Batchs@updateBatchs | jwt | activated,api.auth,shoplog |
| GET | `/api/onecode/wxaOneCodeStream` | 获取物品批次小程序码 | Action\Batchs@getWxaOneCodeStream | jwt | activated,api.auth,shoplog |

### `routes/api/openapi.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| GET | `/api/setting/openapi/developer` | 获取开发配置 | Action\DeveloperController@info | jwt | activated |

### `routes/api/order.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| GET | `/api/rights/getdata` | 获取用户权益 | Action\Rights@getRightsListData | jwt | activated |
| GET | `/api/rights/list` | 获取权益列表 | Action\Rights@getRightsList | jwt | activated,datapass |
| POST | `/api/rights` | 新增权益 | Action\Rights@createRights | jwt | activated |
| PUT | `/api/transfer/rights` | 转赠会员权益 | Action\Rights@transferRights | jwt | activated |
| GET | `/api/transfer/rights/list` | 转赠会员权益列表 | Action\Rights@transferRightsList | jwt | activated,datapass |
| GET | `/api/rights/log` | 获取权益核销列表 | Action\RightsLogs@getLogsList | jwt | activated,datapass |
| GET | `/api/rights/info` | 获取权益核销详情 | Action\Rights@getRightsInfo | jwt | activated |
| POST | `/api/rights/delay` | 权益延期 | Action\Rights@delayRights | jwt | activated |
| GET | `/api/delivery/process/list` | 订单物流日志 | Action\Delivery@processLogList | jwt | activated |
| GET | `/api/shipping/templates/list` | 获取运费模板列表 | Action\ShippingTemplate@getShippingTemplatesList | jwt | activated |
| GET | `/api/shipping/templates/info/{id}` | 获取运费模板详情 | Action\ShippingTemplate@getShippingTemplatesInfo | jwt | activated |
| POST | `/api/shipping/templates/create` | 添加运费模板 | Action\ShippingTemplate@createShippingTemplates | jwt | activated |
| PUT | `/api/shipping/templates/update/{id}` | 更新运费模板 | Action\ShippingTemplate@updateShippingTemplates | jwt | activated |
| DELETE | `/api/shipping/templates/delete/{id}` | 删除运费模板 | Action\ShippingTemplate@deleteShippingTemplates | jwt | activated |
| GET | `/api/trade/logistics/list` | 获取可用物流列表 | Action\CompanyRelLogistics@getLogisticsList | jwt | activated |
| GET | `/api/company/logistics/list` | 获取公司启用物流列表 | Action\CompanyRelLogistics@getCompanyLogisticsList | jwt | activated |
| POST | `/api/company/logistics/create` | 创建公司启用物流 | Action\CompanyRelLogistics@createCompanyLogistics | jwt | activated |
| DELETE | `/api/company/logistics/{id}` | 删除公司关闭物流 | Action\CompanyRelLogistics@deleteCompanyLogistics | jwt | activated |
| GET | `/api/company/logistics/qinglongcode` | 设置公司青龙物流编码 | Action\CompanyRelLogistics@getQinglongcode | jwt | activated |
| POST | `/api/company/logistics/qinglongcode` | 设置公司青龙物流编码 | Action\CompanyRelLogistics@setQinglongcode | jwt | activated |
| POST | `/api/company/dada/create` | 创建公司启用达达同城配 | Action\CompanyRelDada@createCompanyRelDada | jwt | api.auth,shoplog,activated |
| GET | `/api/company/dada/info` | 获取公司达达同城配信息 | Action\CompanyRelDada@getCompanyRelDadaInfo | jwt | api.auth,shoplog,activated |
| GET | `/api/company/delivery` | 获取商户同城配商家自配信息 | Action\CompanyRelDeliveryController@getInfo | jwt | api.auth,shoplog,activated |
| POST | `/api/company/delivery` | 更新商户同城配商家自配信息 | Action\CompanyRelDeliveryController@save | jwt | api.auth,shoplog,activated |
| POST | `/api/company/shansong/info` | 保存商户闪送应用配置信息 | Action\CompanyRelShansong@saveInfo | jwt | api.auth,shoplog,activated |
| GET | `/api/company/shansong/info` | 获取商户闪送应用配置信息 | Action\CompanyRelShansong@getInfo | jwt | api.auth,shoplog,activated |
| GET | `/api/order/message/new` | 店务端未读消息 | Action\OrderMessage@getNewInfo | jwt | activated |
| GET | `/api/order/message/list` | 店务端消息列表 | Action\OrderMessage@getList | jwt | activated |
| POST | `/api/order/message/update` | 店务端更新消息 | Action\OrderMessage@updateMsg | jwt | activated |
| GET | `/api/order/offline_payment/get_list` | 线下转账列表 | Action\OfflinePayment@getList | jwt | activated |
| GET | `/api/order/offline_payment/get_info` | 线下转账详情 | Action\OfflinePayment@getInfo | jwt | activated |
| POST | `/api/order/offline_payment/do_check` | 线下转账审核 | Action\OfflinePayment@doCheck | jwt | activated |
| POST | `/api/order/offline_payment/export_data` | 导出线下转账数据 | Action\OfflinePayment@exportData | jwt | activated |
| GET | `/api/statement/period/default/setting` | 获取默认结算周期配置 | Action\StatementPeriodSetting@getDefaultSetting | jwt | activated |
| GET | `/api/statement/period/distributor/setting` | 获取店铺结算周期配置 | Action\StatementPeriodSetting@getDistributorSetting | jwt | activated |
| GET | `/api/statement/period/supplier/setting` | 获取供应商结算周期配置 | Action\StatementPeriodSetting@getSupplierSetting | jwt | activated |
| POST | `/api/statement/period/setting` | 保存结算周期设置 | Action\StatementPeriodSetting@saveSetting | jwt | activated |
| GET | `/api/statement/summarized` | 获取结算汇总数据 | Action\Statements@getSummarized | jwt | activated |
| POST | `/api/statement/summarized/export` | 导出结算汇总数据 | Action\Statements@exportSummarized | jwt | activated |
| POST | `/api/statement/confirm/{statement_id}` | 确认结算 | Action\Statements@comfirmStatement | jwt | activated |
| GET | `/api/statement/detail/{statement_id}` | 获取结算明细数据 | Action\Statements@getDetail | jwt | activated |
| POST | `/api/statement/detail/export` | 导出结算明细数据 | Action\Statements@exportDetail | jwt | activated |
| POST | `/api/checkout` | 购物车结算列表 | Action\NormalOrder@checkout | jwt | api.auth,activated |
| POST | `/api/order/create` | 代客下单 | Action\NormalOrder@createUserOrder | jwt | api.auth,activated |
| POST | `/api/order/payment` | 下单支付 | Action\NormalOrder@payment | jwt | api.auth,activated |
| GET | `/api/order/payment/query` | 支付结果查询 | Action\NormalOrder@queryPayment | jwt | api.auth,activated |
| GET | `/api/supplier/get_order_list` | 供应商查询订单 | Action\SupplierOrder@getOrderList | jwt | api.auth,activated |
| POST | `/api/supplier/order_paid_confirm` | 供应商确认订单付款 | Action\SupplierOrder@orderPaidConfirm | jwt | api.auth,activated |

### `routes/api/pagestemplate.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| POST | `/api/pagestemplate/set` | 模板展示设置 | Action\PagesTemplateSet@set | jwt | api.auth,activated,shoplog |
| GET | `/api/pagestemplate/setInfo` | 模板展示设置信息 | Action\PagesTemplateSet@getInfo | jwt | api.auth,activated,shoplog |
| GET | `/api/pagestemplate/lists` | 模板列表 | Action\PagesTemplate@lists | jwt | api.auth,activated,shoplog |
| POST | `/api/pagestemplate/add` | 新增模板 | Action\PagesTemplate@add | jwt | api.auth,activated,shoplog |
| PUT | `/api/pagestemplate/edit` | 编辑模板 | Action\PagesTemplate@edit | jwt | api.auth,activated,shoplog |
| GET | `/api/pagestemplate/detail` | 模板详情 | Action\PagesTemplate@detail | jwt | api.auth,activated,shoplog |
| GET | `/api/pagestemplate/widget/items` | 获取模板组件商品 | Action\PagesTemplate@getWidgetItems | jwt | api.auth,activated,shoplog |
| POST | `/api/pagestemplate/copy` | 复制模板 | Action\PagesTemplate@copy | jwt | api.auth,activated,shoplog |
| DELETE | `/api/pagestemplate/del/{pages_template_id}` | 废弃模板 | Action\PagesTemplate@delete | jwt | api.auth,activated,shoplog |
| PUT | `/api/pagestemplate/modifyStatus` | 模板状态变更 | Action\PagesTemplate@modifyStatus | jwt | api.auth,activated,shoplog |
| PUT | `/api/pagestemplate/sync` | 模板同步 | Action\PagesTemplate@sync | jwt | api.auth,activated,shoplog |
| GET | `/api/openscreenad/set` | 获取设置信息 | Action\OpenScreenAd@getInfo | jwt | api.auth,activated,shoplog |
| POST | `/api/openscreenad/set` | 保存设置信息 | Action\OpenScreenAd@Save | jwt | api.auth,activated,shoplog |
| GET | `/api/pctemplate/lists` | pc模板列表 | Action\PcTemplate@lists | jwt | api.auth,activated,shoplog |
| POST | `/api/pctemplate/add` | 新增pc模板 | Action\PcTemplate@add | jwt | api.auth,activated,shoplog |
| PUT | `/api/pctemplate/edit` | 编辑pc模板 | Action\PcTemplate@edit | jwt | api.auth,activated,shoplog |
| DELETE | `/api/pctemplate/delete/{theme_pc_template_id}` | 删除pc模板 | Action\PcTemplate@delete | jwt | api.auth,activated,shoplog |
| GET | `/api/pctemplate/getHeaderOrFooter` | 获取头部尾部 | Action\PcTemplate@getHeaderOrFooter | jwt | api.auth,activated,shoplog |
| POST | `/api/pctemplate/saveHeaderOrFooter` | 头尾部保存 | Action\PcTemplate@saveHeaderOrFooter | jwt | api.auth,activated,shoplog |
| GET | `/api/pctemplate/getTemplateContent` | 获取pc模板内容 | Action\PcTemplate@getTemplateContent | jwt | api.auth,activated,shoplog |
| GET | `/api/pctemplate/getDecorationContent` | 获取pc模板装修内容 | Action\PcTemplate@getDecorationContent | jwt | api.auth,activated,shoplog |
| POST | `/api/pctemplate/saveTemplateContent` | 保存pc模板内容 | Action\PcTemplate@saveTemplateContent | jwt | api.auth,activated,shoplog |
| GET | `/api/pctemplate/loginPage/setting` | 获取pc登录页设置 | Action\PcTemplate@getLoginPageSetting | jwt | api.auth,activated,shoplog |
| POST | `/api/pctemplate/loginPage/setting` | 保存pc登录页设置 | Action\PcTemplate@saveLoginPageSetting | jwt | api.auth,activated,shoplog |
| POST | `/api/memberCenterShare/set` | 设置会员中心分享信息 | Action\MemberCenterShare@set | jwt | api.auth,activated,shoplog |
| GET | `/api/memberCenterShare/getInfo` | 获取会员中心分享信息 | Action\MemberCenterShare@getInfo | jwt | api.auth,activated,shoplog |
| GET | `/api/sidebar/list` | 获取侧边栏列表 | Action\PagesSideBar@getList | jwt | api.auth,activated,shoplog |
| GET | `/api/sidebar/{id}` | 获取侧边栏详情 | Action\PagesSideBar@getInfo | jwt | api.auth,activated,shoplog |
| POST | `/api/sidebar` | 设置侧边栏设置 | Action\PagesSideBar@create | jwt | api.auth,activated,shoplog |
| PUT | `/api/sidebar/{id}` | 更新侧边栏设置 | Action\PagesSideBar@update | jwt | api.auth,activated,shoplog |
| DELETE | `/api/sidebar/{id}` | 删除侧边栏设置 | Action\PagesSideBar@delete | jwt | api.auth,activated,shoplog |
| GET | `/api/adplace/popup/list` | 获取广告位列表 | Action\PagesAdPlace@getList | jwt | api.auth,activated,shoplog |
| GET | `/api/adplace/popup/{id}` | 获取广告位详情 | Action\PagesAdPlace@getInfo | jwt | api.auth,activated,shoplog |
| POST | `/api/adplace/popup` | 设置广告位 | Action\PagesAdPlace@create | jwt | api.auth,activated,shoplog |
| PUT | `/api/adplace/popup/{id}` | 更新广告位 | Action\PagesAdPlace@update | jwt | api.auth,activated,shoplog |
| DELETE | `/api/adplace/popup/{id}` | 删除广告位 | Action\PagesAdPlace@delete | jwt | api.auth,activated,shoplog |
| POST | `/api/adplace/popup/submit/{id}` | 提交审核广告位 | Action\PagesAdPlace@submit | jwt | api.auth,activated,shoplog |
| POST | `/api/adplace/popup/audit/{id}` | 审核广告位 | Action\PagesAdPlace@audit | jwt | api.auth,activated,shoplog |
| POST | `/api/adplace/popup/withdraw/{id}` | 撤回广告位审核申请 | Action\PagesAdPlace@withdraw | jwt | api.auth,activated,shoplog |
| GET | `/api/adplace/carousel/list` | 获取广告位列表 | Action\PagesAdPlace@getList | jwt | api.auth,activated,shoplog |
| GET | `/api/adplace/carousel/{id}` | 获取广告位详情 | Action\PagesAdPlace@getInfo | jwt | api.auth,activated,shoplog |
| POST | `/api/adplace/carousel` | 设置广告位 | Action\PagesAdPlace@create | jwt | api.auth,activated,shoplog |
| PUT | `/api/adplace/carousel/{id}` | 更新广告位 | Action\PagesAdPlace@update | jwt | api.auth,activated,shoplog |
| DELETE | `/api/adplace/carousel/{id}` | 删除广告位 | Action\PagesAdPlace@delete | jwt | api.auth,activated,shoplog |
| POST | `/api/adplace/carousel/submit/{id}` | 提交审核广告位 | Action\PagesAdPlace@submit | jwt | api.auth,activated,shoplog |
| POST | `/api/adplace/carousel/audit/{id}` | 审核广告位 | Action\PagesAdPlace@audit | jwt | api.auth,activated,shoplog |
| POST | `/api/adplace/carousel/withdraw/{id}` | 撤回广告位审核申请 | Action\PagesAdPlace@withdraw | jwt | api.auth,activated,shoplog |
| GET | `/api/web-menus` | Web菜单列表 | Action\WebMenuAction@index | jwt | api.auth,activated,shoplog |
| POST | `/api/web-menus` | 新建Web菜单 | Action\WebMenuAction@store | jwt | api.auth,activated,shoplog |
| GET | `/api/web-menus/{id}` | Web菜单详情 | Action\WebMenuAction@show | jwt | api.auth,activated,shoplog |
| PUT | `/api/web-menus/{id}` | 编辑Web菜单 | Action\WebMenuAction@update | jwt | api.auth,activated,shoplog |
| DELETE | `/api/web-menus/{id}` | 删除Web菜单 | Action\WebMenuAction@destroy | jwt | api.auth,activated,shoplog |
| POST | `/api/web-menus/{id}/items` | 新增Web菜单项 | Action\WebMenuItemAction@store | jwt | api.auth,activated,shoplog |
| PUT | `/api/web-menus/{id}/items/sort` | Web菜单项排序 | Action\WebMenuItemAction@batchSort | jwt | api.auth,activated,shoplog |
| PUT | `/api/web-menus/{id}/items/{itemId}` | 编辑Web菜单项 | Action\WebMenuItemAction@update | jwt | api.auth,activated,shoplog |
| DELETE | `/api/web-menus/{id}/items/{itemId}` | 删除Web菜单项 | Action\WebMenuItemAction@destroy | jwt | api.auth,activated,shoplog |

### `routes/api/point.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| GET | `/api/point/member` | 用户积分列表 | Action\PointMember@lists | jwt | activated |
| GET | `/api/point/member/export` | 导出用户积分列表 | Action\PointMember@exportData | jwt | activated |
| POST | `/api/point/adjustment` | 积分调整 | Action\PointMember@adjustment | jwt | activated |
| GET | `/api/member/point/rule` | 用户积分规则详情 | Action\PointMemberRule@info | jwt | activated |
| PUT | `/api/member/point/rule` | 用户积分规则设置 | Action\PointMemberRule@save | jwt | activated |
| GET | `/api/member/pointcount/index` | 获取积分总览页数据 | Action\PointMember@getPointCountIndex | jwt | activated |

### `routes/api/pointsmall.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| POST | `/api/pointsmall/goods/items` | 添加商品 | Action\Items@createItems | jwt | api.auth,activated,shoplog |
| POST | `/api/pointsmall/goods/setItemsTemplate` | 更新商品运费模板 | Action\Items@setItemsTemplate | jwt | api.auth,activated,shoplog |
| POST | `/api/pointsmall/goods/setItemsCategory` | 更新商品分类 | Action\Items@setItemsCategory | jwt | api.auth,activated,shoplog |
| GET | `/api/pointsmall/goods/items` | 获取商品列表 | Action\Items@getItemsList | jwt | api.auth,activated,shoplog |
| GET | `/api/pointsmall/goods/items/{item_id}` | 获取商品详情 | Action\Items@getItemsDetail | jwt | api.auth,activated,shoplog |
| DELETE | `/api/pointsmall/goods/items/{item_id}` | 删除商品 | Action\Items@deleteItems | jwt | api.auth,activated,shoplog |
| PUT | `/api/pointsmall/goods/items/{item_id}` | 更新商品 | Action\Items@updateItems | jwt | api.auth,activated,shoplog |
| POST | `/api/pointsmall/goods/setItemsSort` | 更新商品排序 | Action\Items@setItemsSort | jwt | api.auth,activated,shoplog |
| PUT | `/api/pointsmall/goods/itemstoreupdate` | 设置商品库存 | Action\Items@batchUpdateItemStore | jwt | activated |
| PUT | `/api/pointsmall/goods/itemstatusupdate` | 设置商品状态 | Action\Items@batchUpdateItemStatus | jwt | activated |
| POST | `/api/pointsmall/goods/export` | 导出商品信息 | Action\ExportItems@exportItemsData | jwt | api.auth,activated,shoplog |
| POST | `/api/pointsmall/setting` | 保存基础设置 | Action\Setting@saveSetting | jwt | api.auth,activated,shoplog |
| GET | `/api/pointsmall/setting` | 获取基础设置 | Action\Setting@getSetting | jwt | api.auth,activated,shoplog |
| POST | `/api/pointsmall/template/setting` | 保存模板设置 | Action\Setting@saveTemplateSetting | jwt | api.auth,activated,shoplog |
| GET | `/api/pointsmall/template/setting` | 获取模板设置 | Action\Setting@getTemplateSetting | jwt | api.auth,activated,shoplog |

### `routes/api/popularize.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| GET | `/api/popularize/config` | 获取分销配置信息 | Action\SettingController@getConfig | jwt | api.auth,activated,shoplog |
| POST | `/api/popularize/config` | 设置分销配置信息 | Action\SettingController@setConfig | jwt | api.auth,activated,shoplog |
| GET | `/api/popularize/promoter/config` | 获取推广员等级 | Action\SettingController@getPromoterGradeConfig | jwt | api.auth,activated,shoplog |
| POST | `/api/popularize/promoter/config` | 设置推广员等级 | Action\SettingController@setPromoterGradeConfig | jwt | api.auth,activated,shoplog |
| GET | `/api/popularize/promoter/identity/list` | 获取推广员身份列表 | Action\PromoterIdentityController@getPromoteridentityList | jwt | datapass |
| GET | `/api/popularize/promoter/identity/info` | 获取推广员身份详情 | Action\PromoterIdentityController@getPromoteridentityInfo | jwt | datapass |
| POST | `/api/popularize/promoter/identity` | 保存推广员身份 | Action\PromoterIdentityController@savePromoteridentity | jwt | datapass |
| DELETE | `/api/popularize/promoter/identity` | 删除推广员身份 | Action\PromoterIdentityController@deletePromoteridentity | jwt | datapass |
| POST | `/api/popularize/promoter/identity/default` | 默认推广员身份 | Action\PromoterIdentityController@defaultPromoteridentity | jwt | datapass |
| POST | `/api/popularize/promoter/add` | 指定会员成为顶级推广员 | Action\PromoterController@addPromoter | jwt | api.auth,activated,shoplog |
| GET | `/api/popularize/promoter/children` | 获取推广员直属下级列表 | Action\PromoterController@getPromoterchildrenList | jwt | datapass |
| GET | `/api/popularize/promoter/list` | 获取推广员列表 | Action\PromoterController@getPromoterList | jwt | datapass |
| GET | `/api/popularize/promoter/export` | 导出推广员业绩 | Action\PromoterController@exportPromoterList | jwt | datapass |
| GET | `/api/popularize/promoter/exportPopularizeOrder` | 导出推广员业绩 | Action\PromoterController@exportPopularizeOrder | jwt | datapass |
| GET | `/api/popularize/promoter/exportPopularizeStatic` | 导出推广员业绩 | Action\PromoterController@exportPopularizeStatic | jwt | datapass |
| PUT | `/api/popularize/promoter/grade` | 推广员等级调整 | Action\PromoterController@updatePromoterGrade | jwt | api.auth,activated,shoplog |
| PUT | `/api/popularize/promoter/disabled` | 禁用/激活推广员 | Action\PromoterController@updatePromoterDisabled | jwt | api.auth,activated,shoplog |
| PUT | `/api/popularize/promoter/remove` | 调整推广员上下级关系 | Action\PromoterController@updatePromoterRemove | jwt | api.auth,activated,shoplog |
| PUT | `/api/popularize/promoter/shop` | 对推广员的店铺状态进行更新 | Action\PromoterController@updatePromoterShop | jwt | api.auth,activated,shoplog |
| GET | `/api/popularize/promoter/firstidentitylist` | 获取推广员一级身份列表 | Action\PromoterController@getFirstIdentityPromoter | jwt | datapass |
| PUT | `/api/popularize/promoter/member/remove` | 调整会员上级关系 | Action\PromoterController@updateMemberPromoterRemove | jwt | api.auth,activated,shoplog |
| PUT | `/api/popularize/cash_withdrawals/{cash_withdrawal_id}` | 处理推广员佣金提现申请 | Action\BrokerageController@processCashWithdrawal | jwt | api.auth,activated,shoplog |
| GET | `/api/popularize/cashWithdrawals` | 获取佣金提现列表 | Action\BrokerageController@getCashWithdrawalList | jwt | datapass |
| GET | `/api/popularize/cashWithdrawal/payinfo/{cash_withdrawal_id}` | 获取佣金提现支付信息 | Action\BrokerageController@getMerchantTradeList | jwt | api.auth,activated,shoplog |
| GET | `/api/popularize/brokerage/count` | 获取佣金统计 | Action\BrokerageController@brokerageCount | jwt | api.auth,activated,shoplog |
| GET | `/api/popularize/brokerage/logs` | 获取佣金记录 | Action\BrokerageController@getBrokerageList | jwt | api.auth,activated,shoplog |
| GET | `/api/popularize/taskBrokerage/logs` | 获取任务佣金记录 | Action\BrokerageController@getTaskBrokerageList | jwt | datapass |
| GET | `/api/popularize/taskBrokerage/count` | 获取任务佣金统计 | Action\BrokerageController@getTaskBrokerageCountList | jwt | datapass |
| GET | `/api/popularize/export/taskBrokerage/count` | 获取任务佣金统计 | Action\BrokerageController@exportTaskBrokerageCount | jwt | datapass |

### `routes/api/promotions.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| GET | `/api/promotions/register` | 获取注册引导营销配置 | Action\RegisterPromotions@getRegisterPromotionsConfig | jwt | activated |
| POST | `/api/promotions/register` | 注册引导营销配置设置 | Action\RegisterPromotions@saveRegisterPromotionsConfig | jwt | activated |
| GET | `/api/promotions/point` | 获取注册积分配置 | Action\RegisterPromotions@getRegisterPointConfig | jwt | activated |
| POST | `/api/promotions/point` | 注册积分配置 | Action\RegisterPromotions@saveRegisterPointConfig | jwt | activated |
| GET | `/api/sms/basic` | 短信账户基本信息 | Action\Sms@getSmsBasic | jwt | activated |
| GET | `/api/sms/templates` | 获取短信模版列表 | Action\Sms@getSmsTemplateList | jwt | activated |
| GET | `/api/sms/template/detail` | 获取短信模版详情 | Action\Sms@getSmsTemplateDetail | jwt | activated |
| PATCH | `/api/sms/template` | 更新短信模版配置 | Action\Sms@updateSmsTemplate | jwt | activated |
| GET | `/api/sms/sign` | 获取短信签名 | Action\Sms@getSmsSign | jwt | activated |
| POST | `/api/sms/sign` | 设置短信签名 | Action\Sms@saveSmsSign | jwt | activated |
| POST | `/api/sms/send/test` | 发送测试短信 | Action\Sms@sendSmsTest | jwt | activated |
| GET | `/api/wxa/notice/templates` | 小程序通知消息模版 | Action\WxaTemplate@getWxaTemplateList | jwt | activated |
| PUT | `/api/wxa/notice/templates` | 开通小程序通知消息模版 | Action\WxaTemplate@openWxaTemplate | jwt | activated |
| GET | `/api/ali/notice/templates` | 小程序通知消息模版 | Action\AliTemplate@getAliTemplateList | jwt | activated |
| PUT | `/api/ali/notice/templates` | 开通小程序通知消息模版 | Action\AliTemplate@openAliTemplate | jwt | activated |
| POST | `/api/promotions/bargains` | 创建助力活动 | Action\BargainPromotions@createBargain | jwt | activated |
| GET | `/api/promotions/bargains` | 获取助力活动列表 | Action\BargainPromotions@getBargainList | jwt | activated |
| GET | `/api/promotions/bargains/{bargain_id}` | 获取助力活动详情 | Action\BargainPromotions@getBargainDetail | jwt | activated |
| PUT | `/api/promotions/bargains/{bargain_id}` | 更新助力活动 | Action\BargainPromotions@updateBargain | jwt | activated |
| PUT | `/api/promotions/bargains/termination/{bargain_id}` | 终止助力活动 | Action\BargainPromotions@terminateBargain | jwt | activated |
| DELETE | `/api/promotions/bargains/{bargain_id}` | 删除助力活动 | Action\BargainPromotions@deleteBargain | jwt | activated |
| POST | `/api/promotions/activity/validNum` | 检查当前营销活动的有效数量 | Action\ActivityPromotions@checkActiveValidNum | jwt | activated |
| PUT | `/api/promotions/activity/invalid` | 将当前自动化营销活动失效 | Action\ActivityPromotions@updateStatusInvalid | jwt | activated |
| POST | `/api/promotions/activity/create` | 创建自动化营销活动 | Action\ActivityPromotions@createActivity | jwt | activated |
| GET | `/api/promotions/activity/lists` | 获取自动化营销活动列表 | Action\ActivityPromotions@getActivityList | jwt | activated |
| POST | `/api/promotions/activity/give` | 后台发放优惠券 | Action\GivePromotions@give | jwt | activated |
| GET | `/api/promotions/activity/give` | 优惠券发放日志 | Action\GivePromotions@getGiveLog | jwt | activated |
| GET | `/api/promotions/activity/give/{id}` | 优惠券赠送失败记录 | Action\GivePromotions@getGiveErrorLog | jwt | activated,datapass |
| GET | `/api/promotions/groups` | 获取拼团活动列表 | Action\PromotionGroupsActivity@getPromotionGroupsActivityList | jwt | activated |
| GET | `/api/promotions/groups/{groupId}` | 获取拼团活动详情 | Action\PromotionGroupsActivity@getPromotionGroupsActivityDetail | jwt | activated |
| GET | `/api/promotions/groups/{groupId}/team` | 获取拼团数据详情 | Action\PromotionGroupsActivity@getPromotionGroupsTeamList | jwt | activated |
| GET | `/api/promotions/groups/team/{teamId}` | 获取拼团数据成员详情 | Action\PromotionGroupsActivity@getPromotionGroupsTeamInfo | jwt | activated |
| POST | `/api/promotions/groups` | 创建拼团活动 | Action\PromotionGroupsActivity@createPromotionGroupsActivity | jwt | activated |
| PUT | `/api/promotions/groups/{groupId}` | 更新拼团活动 | Action\PromotionGroupsActivity@updatePromotionGroupsActivity | jwt | activated |
| PUT | `/api/promotions/groups/finish/{groupId}` | 结束拼团活动 | Action\PromotionGroupsActivity@finishPromotionGroupsActivity | jwt | activated |
| DELETE | `/api/promotions/groups/{groupId}` | 删除拼团活动 | Action\PromotionGroupsActivity@deletePromotionGroupsActivity | jwt | activated |
| POST | `/api/promotions/turntableconfig` | 修改大转盘配置 | Action\Turntable@setTurntableConfig | jwt | activated |
| GET | `/api/promotions/turntableconfig` | 获取大转盘配置 | Action\Turntable@getTurntableConfig | jwt | activated |
| GET | `/api/promotions/getturntableList` | 获取大转盘列表 | Action\Turntable@getDrawActivityList | jwt | activated |
| GET | `/api/promotions/getturntable` | 获取大转盘详情 | Action\Turntable@getLuckyDrawDetail | jwt | activated |
| GET | `/api/promotions/getturntable_log/byid` | 获取大转盘日志 | Action\Turntable@getLogStatistics | jwt | activated |
| GET | `/api/promotions/getturntable_count/byid` | 获取大转盘日志统计 | Action\Turntable@getLogCount | jwt | activated |
| GET | `/api/promotions/down_lucky_draw/byid` | 活动下架 | Action\Turntable@downLuckyDrawActivity | jwt | activated |
| GET | `/api/promotions/down_lucky_draw/export` | 活动日志导出 | Action\Turntable@exportLog | jwt | activated |
| POST | `/api/promotions/activearticle` | 添加活动文章 | Action\ActivityPromotions@saveActiveArticle | jwt | activated |
| GET | `/api/promotions/activearticle/list` | 获取活动文章列表 | Action\ActivityPromotions@getActiveArticleList | jwt | activated |
| GET | `/api/promotions/activearticle/{id}` | 获取活动文章详情 | Action\ActivityPromotions@getActiveArticleDetail | jwt | activated |
| PUT | `/api/promotions/activearticle` | 修改活动文章 | Action\ActivityPromotions@updateActiveArticle | jwt | activated |
| DELETE | `/api/promotions/activearticle/{id}` | 删除活动文章 | Action\ActivityPromotions@deleteActiveArticle | jwt | activated |
| POST | `/api/promotions/extrapoint` | 创建额外积分活动 | Action\ExtraPointActivity@createActivity | jwt | activated |
| PUT | `/api/promotions/extrapoint` | 修改额外积分活动 | Action\ExtraPointActivity@updateActivity | jwt | activated |
| GET | `/api/promotions/extrapoint/lists` | 额外积分活动列表 | Action\ExtraPointActivity@getActivityList | jwt | activated |
| PUT | `/api/promotions/extrapoint/invalid` | 将当前额外积分活动失效 | Action\ExtraPointActivity@updateStatusInvalid | jwt | activated |
| GET | `/api/promotions/extrapoint/{id}` | 获取额外积分活动详情 | Action\ExtraPointActivity@getActivityInfo | jwt | activated |
| GET | `/api/promotions/package` | 组合商品活动列表 | Action\PackagePromotions@lists | jwt | activated |
| GET | `/api/promotions/package/{packageId}` | 获取组合商品活动 | Action\PackagePromotions@info | jwt | activated |
| POST | `/api/promotions/package` | 添加组合商品活动 | Action\PackagePromotions@create | jwt | activated |
| PUT | `/api/promotions/package/{packageId}` | 修改组合商品活动 | Action\PackagePromotions@update | jwt | activated |
| DELETE | `/api/promotions/package/cancel/{packageId}` | 取消组合商品活动 | Action\PackagePromotions@cancel | jwt | activated |
| GET | `/api/promotions/limit` | 限购活动列表 | Action\LimitPromotions@lists | jwt | activated |
| GET | `/api/promotions/limit_error_desc` | 下载商品限购错误信息 | Action\LimitPromotions@exportErrorDesc | jwt | activated |
| GET | `/api/promotions/limit/{limitId}` | 获取限购活动详情 | Action\LimitPromotions@info | jwt | activated |
| POST | `/api/promotions/limit` | 添加限购活动 | Action\LimitPromotions@create | jwt | activated |
| PUT | `/api/promotions/limit/{limitId}` | 修改限购活动 | Action\LimitPromotions@update | jwt | activated |
| POST | `/api/promotions/limit_items/upload` | 上传限购商品文件 | Action\LimitPromotions@uploadLimitItems | jwt | activated |
| GET | `/api/promotions/limit_items/{limitId}` | 获取限购商品列表 | Action\LimitPromotions@getLimitItems | jwt | activated |
| DELETE | `/api/promotions/limit_items/{limitId}` | 删除限购商品 | Action\LimitPromotions@deleteLimitItem | jwt | activated |
| PUT | `/api/promotions/limit_items/{limitId}` | 更新限购商品数量 | Action\LimitPromotions@updateLimitItem | jwt | activated |
| POST | `/api/promotions/limit_items_save` | 保存限购商品 | Action\LimitPromotions@saveLimitItems | jwt | activated |
| DELETE | `/api/promotions/limit/cancel/{limitId}` | 取消限购活动 | Action\LimitPromotions@cancel | jwt | activated |
| GET | `/api/promotions/liverooms` | 获取直播、回放视频列表 | Action\ActivityPromotions@getLiveRooms | jwt | activated |
| POST | `/api/promotions/register/distributor` | 创建/修改 注册促销(分销商) | Action\RegisterController@createRegister | jwt | activated |
| GET | `/api/promotions/register/distributor` | 获取注册促销列表(分销商) | Action\RegisterController@getRegisterList | jwt | activated |
| GET | `/api/promotions/register/distributor/{id}` | 获取注册促销详情(分销商) | Action\RegisterController@getRegisterInfo | jwt | activated |
| DELETE | `/api/promotions/register/distributor/{id}` | 删除注册促销(分销商) | Action\RegisterController@deleteRegister | jwt | activated |
| GET | `/api/promotions/distributor` | 获取分销商列表 | Action\RegisterController@getDistributorList | jwt | activated |
| POST | `/api/promotions/seckillactivity/create` | 创建秒杀活动 | Action\SeckillActivity@createSeckillActivity | jwt | activated |
| PUT | `/api/promotions/seckillactivity/update` | 修改秒杀活动 | Action\SeckillActivity@updateSeckillActivity | jwt | activated |
| GET | `/api/promotions/seckillactivity/getlist` | 获取秒杀活动列表 | Action\SeckillActivity@getSeckillActivityList | jwt | activated |
| GET | `/api/promotions/seckillactivity/getinfo` | 创建秒杀活动 | Action\SeckillActivity@getSeckillActivityInfo | jwt | activated |
| PUT | `/api/promotions/seckillactivity/updatestatus` | 更新秒杀活动状态 | Action\SeckillActivity@updateStatus | jwt | activated |
| GET | `/api/promotions/seckillactivity/getIteminfo` | 获取秒杀活动商品列表 | Action\SeckillActivity@getSeckillItemList | jwt | activated |
| GET | `/api/promotions/seckillactivity/wxcode` | 获取秒杀活动小程序码 | Action\SeckillActivity@getSeckillWxaCode | jwt | activated |
| GET | `/api/promotions/seckillactivity/search/items` | 根据条件获取商品列表 | Action\SeckillActivity@searchItems | jwt | activated |
| POST | `/api/marketing/create` | 创建满折促销活动 | Action\MarketingActivity@createMarketingActivity | jwt | activated |
| DELETE | `/api/marketing/delete` | 删除满折促销活动 | Action\MarketingActivity@deleteMarketingActivity | jwt | activated |
| PUT | `/api/marketing/update` | 修改满折促销活动 | Action\MarketingActivity@updateMarketingActivity | jwt | activated |
| GET | `/api/marketing/getlist` | 获取满折促销活动列表 | Action\MarketingActivity@getMarketingActivityList | jwt | activated |
| GET | `/api/marketing/getinfo` | 获取满折促销活动详情 | Action\MarketingActivity@getMarketingActivityInfo | jwt | activated |
| GET | `/api/marketing/getItemList` | 获取满折促销活动商品列表 | Action\MarketingActivity@getActivityItemList | jwt | activated |
| GET | `/api/promotions/recommendlike` | 获取猜你喜欢商品列表 | Action\RecommendLikeController@getRecommendLikeLists | jwt | activated |
| PUT | `/api/promotions/recommendlike` | 编辑猜你喜欢商品 | Action\RecommendLikeController@updateRecommendLike | jwt | activated |
| GET | `/api/promotions/recommendlikes` | 获取猜你喜欢商品 | Action\RecommendLikeController@getRecommendLikeItems | jwt | activated |
| POST | `/api/promotions/recommendlike` | 添加猜你喜欢商品 | Action\RecommendLikeController@createRecommendLike | jwt | activated |
| DELETE | `/api/promotions/recommendlike/{id}` | 删除猜你喜欢商品 | Action\RecommendLikeController@delRecommendLike | jwt | activated |
| POST | `/api/specific/crowd/discount` | 创建定向促销 | Action\SpecificCrowdDiscount@createSpecificCrowdDiscount | jwt | activated |
| PUT | `/api/specific/crowd/discount` | 更新定向促销 | Action\SpecificCrowdDiscount@updateSpecificCrowdDiscount | jwt | activated |
| GET | `/api/specific/crowd/discountList` | 获取定向促销列表 | Action\SpecificCrowdDiscount@getSpecificCrowdDiscountList | jwt | activated |
| GET | `/api/specific/crowd/discountInfo` | 获取定向促销详情 | Action\SpecificCrowdDiscount@getSpecificCrowdDiscountInfo | jwt | activated |
| GET | `/api/specific/crowd/discountLogList` | 获取定向促销优惠日志 | Action\SpecificCrowdDiscount@getSpecificcrowddiscountLogList | jwt | activated,datapass |
| GET | `/api/promotions/pointupvaluation/lists` | 获取积分升值活动列表 | Action\PointupvaluationActivity@getActivityList | jwt | activated |
| POST | `/api/promotions/pointupvaluation/create` | 创建积分升值活动 | Action\PointupvaluationActivity@createActivity | jwt | activated |
| PUT | `/api/promotions/pointupvaluation/update` | 修改积分升值活动 | Action\PointupvaluationActivity@updateActivity | jwt | activated |
| GET | `/api/promotions/pointupvaluation/getinfo` | 获取积分升值活动详情 | Action\PointupvaluationActivity@getActivityInfo | jwt | activated |
| PUT | `/api/promotions/pointupvaluation/updatestatus` | 更新积分升值活动状态 | Action\PointupvaluationActivity@updateStatus | jwt | activated |
| GET | `/api/promotions/checkin/getlist` | 获取会员签到记录列表 | Action\CheckInController@getCheckInList | jwt | activated |

### `routes/api/reservation.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| POST | `/api/reservation/setting` | 保存预约的详细配置 | Action\Setting@setSetting | jwt | activated |
| GET | `/api/reservation/setting` | 预约配置详细信息 | Action\Setting@getSetting | jwt | activated |
| GET | `/api/resource/level/{id}` | 获取资源位详细信息 | Action\ResourceLevel@getData | jwt | activated |
| GET | `/api/resource/levellist` | 获取资源位列表 | Action\ResourceLevel@getListData | jwt | activated |
| PUT | `/api/resource/setlevelstatus` | 修改资源状态 | Action\ResourceLevel@updateResourceLevelStatus | jwt | activated |
| GET | `/api/shifttype` | 排班类型列表 | Action\WorkShiftType@getListShiftType | jwt | activated |
| GET | `/api/workshift` | 排班列表 | Action\WorkShift@getListWorkShift | jwt | activated |
| GET | `/api/getweekday` | 获取每年的周日期 | Action\WorkShift@getEveryYearWeeks | jwt | activated |
| GET | `/api/workshift/default` | 获取门店默认排班 | Action\DefaultWorkShift@getDefaultWorkShift | jwt | activated |
| GET | `/api/reservation` | 查看预约记录 | Action\Reservation@getList | jwt | activated |
| GET | `/api/reservation/period` | 获取每天预约时间段 | Action\Reservation@getEveryDayTimePeriod | jwt | activated |

### `routes/api/selfService.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| POST | `/api/selfhelp/formdata` | 新增表单元素配置项 | Action\FormSettingController@createData | jwt | activated |
| PUT | `/api/selfhelp/formdata` | 更新表单元素配置项 | Action\FormSettingController@updateData | jwt | activated |
| GET | `/api/selfhelp/formdata` | 获取表单元素配置项列表 | Action\FormSettingController@getDatalist | jwt | activated |
| GET | `/api/selfhelp/formdata/{id}` | 获取表单元素配置项详情 | Action\FormSettingController@getDataInfo | jwt | activated |
| POST | `/api/selfhelp/formdata/discard/{id}` | 废弃表单元素配置项 | Action\FormSettingController@deleteData | jwt | activated |
| POST | `/api/selfhelp/formdata/restore/{id}` | 恢复表单元素配置项 | Action\FormSettingController@restoreData | jwt | activated |
| POST | `/api/selfhelp/formtem` | 新增表单模板 | Action\FormTemplateController@createData | jwt | activated |
| PUT | `/api/selfhelp/formtem` | 更新表单模板 | Action\FormTemplateController@updateData | jwt | activated |
| GET | `/api/selfhelp/formtem` | 获取表单模板列表 | Action\FormTemplateController@getDatalist | jwt | activated |
| GET | `/api/selfhelp/formtem/{id}` | 获取表单模板详情 | Action\FormTemplateController@getDataInfo | jwt | activated |
| POST | `/api/selfhelp/formtem/discard/{id}` | 废弃表单模板 | Action\FormTemplateController@deleteData | jwt | activated |
| POST | `/api/selfhelp/formtem/restore/{id}` | 恢复表单模板 | Action\FormTemplateController@restoreData | jwt | activated |
| POST | `/api/selfhelp/setting/physical` | 配置体测表单 | Action\UserDailyRecordController@settingPhysical | jwt | activated |
| GET | `/api/selfhelp/setting/physical` | 获取体测表单配置 | Action\UserDailyRecordController@getSettingPhysical | jwt | activated |
| GET | `/api/selfhelp/physical/alluserlist` | 获取体测数据所有会员（最近一次的记录） | Action\UserDailyRecordController@getAllUserList | jwt | activated |
| GET | `/api/selfhelp/physical/userdata` | 获取指定会员最近5次的体测记录 | Action\UserDailyRecordController@getUserPersonalRecord | jwt | activated |
| GET | `/api/selfhelp/physical/datelist` | 获取所有记录的日期列表 | Action\UserDailyRecordController@getRecordDateList | jwt | activated |
| POST | `/api/selfhelp/registrationActivity/create` | 新增报名活动 | Action\RegistrationActivityController@createData | jwt | activated |
| PUT | `/api/selfhelp/registrationActivity/update` | 更新报名活动 | Action\RegistrationActivityController@updateData | jwt | activated |
| PUT | `/api/selfhelp/registrationActivity/setIsShow` | 设置报名活动展示状态 | Action\RegistrationActivityController@setIsShow | jwt | activated |
| GET | `/api/selfhelp/registrationActivity/list` | 获取报名活动列表 | Action\RegistrationActivityController@getDatalist | jwt | activated |
| GET | `/api/selfhelp/registrationActivity/get` | 获取报名活动详情 | Action\RegistrationActivityController@getDataInfo | jwt | activated |
| POST | `/api/selfhelp/registrationActivity/del` | 删除报名活动 | Action\RegistrationActivityController@deleteData | jwt | activated |
| POST | `/api/selfhelp/registrationActivity/invalid` | 过期报名活动 | Action\RegistrationActivityController@restoreData | jwt | activated |
| GET | `/api/selfhelp/registrationActivity/easylist` | 获取报名活动列表 | Action\RegistrationActivityController@getEasyDatalist | jwt | activated |
| GET | `/api/selfhelp/registrationRecord/list` | 获取表单模板列表 | Action\RegistrationRecordController@getDatalist | jwt | activated,datapass |
| GET | `/api/selfhelp/registrationRecord/get` | 获取表单模板详情 | Action\RegistrationRecordController@getDataInfo | jwt | activated,datapass |
| POST | `/api/selfhelp/registrationRecord/update` | 更新报名记录 | Action\RegistrationRecordController@updateDataInfo | jwt | activated,datapass |
| PUT | `/api/selfhelp/registrationReview` | 报名审核 | Action\RegistrationRecordController@registrationReview | jwt | activated |
| POST | `/api/selfhelp/registrationVerify` | 报名记录核销 | Action\RegistrationRecordController@registrationVerify | jwt | activated |
| GET | `/api/selfhelp/registrationVerifyLog` | 报名记录核销记录列表 | Action\RegistrationRecordController@registrationVerifyLog | jwt | activated |
| GET | `/api/selfhelp/registrationRecord/export` | 导出报名记录 | Action\RegistrationRecordController@exportRegistrationRecord | jwt | activated,datapass |

### `routes/api/shopexai.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| POST | `/api/outfit/generate` |  |  |  |  |
| POST | `/api/member/outfit/model` |  |  |  |  |
| DELETE | `/api/member/outfit/model/{id}` |  |  |  |  |
| GET | `/api/member/outfit/logs` |  |  |  |  |

### `routes/api/shopmenu.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| GET | `/api/shopmenu` |  | Action\ShopMenu@getShopMenu | jwt | api.auth,activated,shoplog |
| POST | `/api/shopmenu` |  | Action\ShopMenu@addShopMenu | jwt | api.auth,activated,shoplog |
| PUT | `/api/shopmenu` |  | Action\ShopMenu@updateShopMenu | jwt | api.auth,activated,shoplog |
| DELETE | `/api/shopmenu/{shopmenuId}` |  | Action\ShopMenu@deleteShopMenu | jwt | api.auth,activated,shoplog |
| GET | `/api/shopmenu/down` |  | Action\ShopMenu@downShopMenu | jwt | api.auth,activated,shoplog |
| POST | `/api/shopmenu/upload` |  | Action\ShopMenu@uploadMenu | jwt | api.auth,activated,shoplog |

### `routes/api/shuyun_open_platform.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| GET | `/api/shuyun/open-platform/config` | 数云开放网关配置-获取 | Action\OpenPlatformConfigController@getConfig | jwt | api.auth,activated,shoplog |
| PUT | `/api/shuyun/open-platform/config` | 数云开放网关配置-保存 | Action\OpenPlatformConfigController@putConfig | jwt | api.auth,activated,shoplog |
| POST | `/api/shuyun/open-platform/loyalty/grade/sync` | 数云开放网关-会员等级档案手动同步 | Action\LoyaltyGradeSyncController@postManualSync | jwt | api.auth,activated,shoplog |
| POST | `/api/shuyun/open-platform/callback/token` |  | Controllers\ShuyunOpenPlatformTokenCallbackController@token |  |  |
| POST | `/api/shuyun/open-platform/callback/loyalty-grade` |  | Controllers\ShuyunOpenPlatformLoyaltyGradeCallbackController@callback |  |  |
| POST | `/api/shuyun/open-platform/callback/offline-benefit/create` |  | Controllers\ShuyunOfflineBenefitCallbackController@create |  |  |
| POST | `/api/shuyun/open-platform/callback/offline-benefit/single-send` |  | Controllers\ShuyunOfflineBenefitCallbackController@singleSend |  |  |
| POST | `/api/shuyun/open-platform/callback/offline-benefit/batch-send` |  | Controllers\ShuyunOfflineBenefitCallbackController@batchSend |  |  |

### `routes/api/tdkset.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| GET | `/api/pcdecoration/tdkglobalset` | 获取TDK全局配置信息 | Action\TdkGlobalSet@getInfo | jwt | api.auth,activated,shoplog |
| POST | `/api/pcdecoration/tdkglobalset` | TDK全局信息添加&修改 | Action\TdkGlobalSet@Save | jwt | api.auth,activated,shoplog |
| GET | `/api/pcdecoration/tdkgivenset/{type}` | 获取TDK特定页面配置信息 | Action\TdkGivenSet@getInfo | jwt | api.auth,activated,shoplog |
| POST | `/api/pcdecoration/tdkgivenset/{type}` | TDK特定页面信息添加&修改 | Action\TdkGivenSet@Save | jwt | api.auth,activated,shoplog |

### `routes/api/third.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| POST | `/api/third/shopexerp/setting` | shopexerp配置信息保存 | Action\Third@setShopexErpSetting | jwt | api.auth,shoplog,activated |
| GET | `/api/third/shopexerp/setting` | 获取shopexerp配置信息保存 | Action\Third@getShopexErpSetting | jwt | api.auth,shoplog,activated |
| GET | `/api/omsqueuelog` | 获取oms通信日志列表 | Action\OmsQueueLogController@getLogList | jwt | api.auth,shoplog,activated |
| POST | `/api/third/wdterp/setting` | 旺店通配置信息保存 | Action\WdtErp@setSetting | jwt | activated |
| GET | `/api/third/wdterp/setting` | 获取旺店通配置信息保存 | Action\WdtErp@getSetting | jwt | activated |
| POST | `/api/third/jushuitan/setting` | 聚水潭配置信息保存 | Action\Jushuitan@setSetting | jwt | activated |
| GET | `/api/third/jushuitan/setting` | 获取聚水潭配置信息保存 | Action\Jushuitan@getSetting | jwt | activated |
| POST | `/api/third/map/setting` | 更新第三方地图定位的类型 | Action\MapController@set | jwt | api.auth,shoplog,activated |
| GET | `/api/third/map/setting` | 获取第三方地图定位的类型 | Action\MapController@get | jwt | api.auth,shoplog,activated |
| POST | `/api/third/oms/setting` | OMS对接配置 | Action\OmsController@setOmsSetting | jwt | api.auth,shoplog,activated |
| GET | `/api/third/oms/setting` | 获取OMS对接配置 | Action\OmsController@getOmsSetting | jwt | api.auth,shoplog,activated |
| POST | `/api/third/dmcrm/setting` | 达摩CRM配置信息保存 | Action\DmCrm@setSetting | jwt | activated |
| GET | `/api/third/dmcrm/setting` | 获取达摩CRM配置信息保存 | Action\DmCrm@getSetting | jwt | activated |

### `routes/api/trade.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| GET | `/api/paypal/success` | Paypal支付回调 | PaymentBundle\Http\Controllers\PaypalNotify@handle |  |  |
| POST | `/api/alipay/notify` | 支付宝支付回调 | PaymentBundle\Http\Controllers\PaymentNotify@handle |  |  |
| POST | `/api/chinaums/notify` | 银联支付回调 | PaymentBundle\Http\Controllers\ChinaumsNotify@handle |  |  |
| GET | `/api/trade` | 获取交易列表 | Action\Trade@getTradelist | jwt | activated,datapass |
| GET | `/api/orders` | 获取订单列表 | Action\Order@getOrderList | jwt | activated,datapass |
| GET | `/api/order/{order_id}` | 获取订单详情 | Action\Order@getOrderDetail | jwt | activated,datapass |
| GET | `/api/order/process/{orderId}` | 获取订单操作详情 | Action\Order@getOrderProcessLog | jwt | activated,datapass |
| GET | `/api/fapiaolist` | 获取发票列表 | Action\Order@getInvoiceList | jwt | activated |
| GET | `/api/fapiaoset` | 发票操作 | Action\Order@setInvoice | jwt | activated |
| GET | `/api/order/{order_id}/cancelinfo` | 获取订单取消信息 | Action\Order@getOrderCancelInfo | jwt | activated |
| POST | `/api/order/{order_id}/confirmcancel` | 确认订单取消审核 | Action\Order@confirmOrderCancel | jwt | activated |
| PUT | `/api/order/{order_id}/processdrug` |  | Action\Order@processDrugOrders | jwt | activated |
| POST | `/api/orders/setting/set` | 订单配置设置 | Action\Order@setOrderSetting | jwt | activated |
| GET | `/api/orders/setting/get` | 获取订单配置 | Action\Order@getOrderSetting | jwt | activated |
| POST | `/api/order/{order_id}/cancel` | 取消订单 | Action\Order@cancelOrder | jwt | activated |
| POST | `/api/delivery` | 订单发货 | Action\Order@delivery | jwt | activated |
| PUT | `/api/delivery/{orders_delivery_id}` | 订单发货信息修改 | Action\Order@updateDelivery | jwt | activated |
| PUT | `/api/old_delivery/{orderId}` | 订单发货信息修改（旧) | Action\Order@updateDeliveryOld | jwt | activated |
| PUT | `/api/remarks/{orderId}` | 订单备注信息修改 | Action\Order@updateRemarks | jwt | activated |
| GET | `/api/delivery/details` | 物流详情 | Action\Order@trackerpull | jwt | activated |
| GET | `/api/delivery/lists` | 发货单列表 | Action\Delivery@lists | jwt | activated |
| POST | `/api/confirmReceipt` | 订单确认送达 | Action\Order@confirmReceipt | jwt | activated |
| GET | `/api/orders/exportdata` | 导出订单列表 | Action\ExportData@exportOrderData | jwt | activated,datapass |
| GET | `/api/invoice/exportdata` | 导出发票列表 | Action\ExportData@exportInvoiceData | jwt | activated |
| GET | `/api/rights/exportdata` | 导出权益列表 | Action\ExportData@exportRightData | jwt | activated,datapass |
| GET | `/api/orders/exportnormaldata` | 导出实体订单列表 | Action\ExportData@exportOrderNormalData | jwt | activated |
| GET | `/api/trades/exportdata` | 导出交易单列表 | Action\ExportData@exportTradeData | jwt | activated,datapass |
| GET | `/api/rights/logExport` | 导出权益核销列表 | Action\ExportData@exportRightConsumeData | jwt | activated,datapass |
| POST | `/api/trade/kuaidi/setting` | 快递配置信息保存 | Action\Kuaidi@setKuaidiSetting | jwt | activated |
| GET | `/api/trade/kuaidi/setting` | 获取快递配置信息 | Action\Kuaidi@getKuaidiSetting | jwt | activated |
| POST | `/api/trade/sfbsp/setting` | 顺丰物流跟踪设置保存 | Action\Sfbsp@setSfbspSetting | jwt | activated |
| GET | `/api/trade/sfbsp/setting` | 获取顺丰物流跟踪设置 | Action\Sfbsp@getSfbspSetting | jwt | activated |
| GET | `/api/trade/refunderrorlogs/list` | 获取退款错误列表 | Action\RefundErrorLogs@getList | jwt | activated |
| PUT | `/api/trade/refunderrorlogs/resubmit/{id}` | 重新提交退款 | Action\RefundErrorLogs@resubmitRefund | jwt | activated |
| GET | `/api/trade/rate` | 获取评价列表 | Action\TradeRate@getTradeRateList | jwt | activated |
| PUT | `/api/trade/rate` | 回复评价 | Action\TradeRate@replyTradeRate | jwt | activated |
| GET | `/api/trade/{rate_id}/rate` | 获取评价详情 | Action\TradeRate@getTradeRateInfo | jwt | activated |
| DELETE | `/api/trade/rate/{rate_id}` | 删除评价 | Action\TradeRate@tradeRateDelete | jwt | activated |
| POST | `/api/invoice/number` | 设置订单发票号 | Action\Order@updateInvoiceNumber | jwt | activated |
| POST | `/api/invoice/invoiced` | 设置订单开票状态 | Action\Order@setInvoiced | jwt | activated |
| GET | `/api/financial/salesreport` | 导出财务销售报表 | Action\ExportData@exportSalesreportData | jwt | activated |
| GET | `/api/writeoff/{order_id}` | 获取自提订单核销信息 | Action\Order@getOrderWriteoffInfo | jwt | activated |
| POST | `/api/writeoff/{order_id}` | 自提订单核销 | Action\Order@orderWriteoff | jwt | activated |
| POST | `/api/qr_writeoff` | 自提订单扫码核销 | Action\Order@orderWriteoffQR | jwt | activated |
| POST | `/api/trade/setting` | 交易配置信息保存 | Action\TradeSetting@setSetting | jwt | activated |
| GET | `/api/trade/setting` | 获取交易配置信息 | Action\TradeSetting@getSetting | jwt | activated |
| POST | `/api/trade/cancel/setting` | 取消订单配置信息保存 | Action\TradeSetting@setCancelSetting | jwt | activated |
| GET | `/api/trade/cancel/setting` | 获取取消订单配置信息 | Action\TradeSetting@getCancelSetting | jwt | activated |
| POST | `/api/businessreceipt/{orderId}` | 达达同城配商家接单 | Action\Order@businessReceipt | jwt | activated |
| POST | `/api/confirm/goods/{orderId}` | 达达同城配商家确认退回 | Action\Order@confirmGoods | jwt | activated |
| POST | `/api/order/markdown` | 订单改价 | Action\Order@markDown | jwt | activated,datapass |
| POST | `/api/order/markdown/confirm` | 订单改价确认 | Action\Order@confirmMarkDown | jwt | activated,datapass |
| POST | `/api/order/deliverypackag/confirm` | 订单打包确认 | Action\Order@confirmDeliveryPackag | jwt | activated |
| POST | `/api/order/deliverystaff/confirm` | 订单自配送员确认 | Action\Order@confirmDeliveryStaff | jwt | activated |
| POST | `/api/order/cancel/deliverystaff` | 订单自配送取消 | Action\Order@cancelDeliveryStaff | jwt | activated |
| GET | `/api/order/payorderinfo/{trade_id}` | 获取支付订单状态信息 | Action\OrderStateInfo@getPayOrderInfo | jwt | activated |
| GET | `/api/order/refundorderinfo/{refund_bn}` | 获取退款订单状态信息 | Action\OrderStateInfo@getRefundOrderInfo | jwt | activated |
| POST | `/api/trade/payment/setting` | 支付配置信息保存 | Action\Payment@setPaymentSetting | jwt | activated |
| GET | `/api/trade/payment/setting` | 获取支付配置信息 | Action\Payment@getPaymentSetting | jwt | activated |
| GET | `/api/trade/payment/open-status` | 获取支付配置开关状态 | Action\Payment@getPaymentOpenStatus | jwt | activated |
| GET | `/api/trade/payment/list` | 获取支付配置信息列表 | Action\Payment@getPaymentSettingList | jwt | activated |
| GET | `/api/trade/payment/hfpayversionstatus` | 获取汇付天下版本状态 | Action\Payment@getHfpayVersionStatus | jwt | activated |
| GET | `/api/trade/payment/rsakey` | 生成RSA密钥 | Action\Payment@genRsaKey | jwt | activated |
| GET | `/api/dada/finance/info` | 获取账户余额 | Action\DadaFinance@queryBalance | jwt | activated |
| POST | `/api/dada/finance/create` | 获取充值链接 | Action\DadaFinance@recharge | jwt | activated |
| POST | `/api/doumen-intl/notify` | 斗门国际支付异步通知 | PaymentBundle\Http\Controllers\DoumenIntlNotify@handle |  |  |

### `routes/api/transcript.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| POST | `/api/transcript` | 创建成绩单 | Action\Transcripts@createTranscript | jwt | activated |
| GET | `/api/transcript/{transcript_id}` | 获取成绩单 | Action\Transcripts@getTranscript | jwt | activated |
| PATCH | `/api/transcript/{transcript_id}` | 获取成绩单 | Action\Transcripts@updateTranscript | jwt | activated |
| DELETE | `/api/transcript/{transcript_id}` | 删除成绩单 | Action\Transcripts@deleteTranscript | jwt | activated |
| POST | `/api/usertranscript` | 创建用户成绩单 | Action\UserTranscripts@createUserTranscript | jwt | activated |
| GET | `/api/usertranscript` | 获取用户成绩单 | Action\UserTranscripts@getUserTranscript | jwt | activated |

### `routes/api/ugc.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| POST | `/api/ugc/post/create` | 发布笔记 | Action\PostController@createPost | jwt | /* 'api.auth,shoplog' */ |
| POST | `/api/ugc/post/edit` | 笔记批量打角标 | Action\PostController@editPost | jwt | /* 'api.auth,shoplog' */ |
| POST | `/api/ugc/post/setBadges` | 笔记批量打角标 | Action\PostController@setBadges | jwt | /* 'api.auth,shoplog' */ |
| POST | `/api/ugc/post/verify` | 审核 | Action\PostController@verifyPost | jwt | /* 'api.auth,shoplog' */ |
| POST | `/api/ugc/post/enable` | 发布/上架 | Action\PostController@enablePost | jwt | /* 'api.auth,shoplog' */ |
| GET | `/api/ugc/post/list` | 获取笔记列表 | Action\PostController@getPostList | jwt | activated |
| GET | `/api/ugc/post/detail` | 笔记详情 | Action\PostController@getPostDetail | jwt | activated |
| POST | `/api/ugc/post/delete` |  | Action\PostController@deletePost | jwt | /* 'api.auth,shoplog' */ |
| POST | `/api/ugc/post/settop` |  | Action\PostController@setTopTopic | jwt | /* 'api.auth,shoplog' */ |
| POST | `/api/ugc/topic/create` | 新建话题 | Action\TopicController@createTopic | jwt | api.auth,shoplog |
| POST | `/api/ugc/topic/verify` | 审核话题 | Action\TopicController@verifyTopic | jwt | api.auth,shoplog |
| POST | `/api/ugc/topic/top` | 置顶话题 | Action\TopicController@topTopic | jwt | api.auth,shoplog |
| POST | `/api/ugc/topic/enable` | 发布/上架话题 | Action\TopicController@enableTopic | jwt | api.auth,shoplog |
| GET | `/api/ugc/topic/list` | 获取话题列表 | Action\TopicController@getTopicList | jwt | activated |
| GET | `/api/ugc/topic/detail` | 话题详情 | Action\TopicController@getTopicDetail | jwt | activated |
| POST | `/api/ugc/topic/delete` |  | Action\TopicController@deleteTopic | jwt | api.auth,shoplog |
| POST | `/api/ugc/topic/settop` |  | Action\TopicController@setTopTopic | jwt | api.auth,shoplog |
| POST | `/api/ugc/tag/create` | 新建图片标签 | Action\TagController@createTag | jwt | api.auth,shoplog |
| POST | `/api/ugc/tag/verify` | 审核图片标签 | Action\TagController@verifyTag | jwt | api.auth,shoplog |
| POST | `/api/ugc/tag/enable` | 发布/上架图片标签 | Action\TagController@enableTag | jwt | api.auth,shoplog |
| GET | `/api/ugc/tag/list` | 获取图片标签列表 | Action\TagController@getTagList | jwt | activated |
| GET | `/api/ugc/tag/detail` | 图片标签详情 | Action\TagController@getTagDetail | jwt | activated |
| DELETE | `/api/ugc/tag/delete` |  | Action\TagController@deleteTag | jwt | api.auth,shoplog |
| POST | `/api/ugc/badge/create` | 新建角标 | Action\BadgeController@createBadge | jwt | api.auth,shoplog |
| GET | `/api/ugc/badge/list` | 获取角标列表 | Action\BadgeController@getBadgeList | jwt | activated |
| GET | `/api/ugc/badge/detail` | 角标详情 | Action\BadgeController@getBadgeDetail | jwt | activated |
| POST | `/api/ugc/badge/delete` | 删除角标 | Action\BadgeController@deleteBadge | jwt | api.auth,shoplog |
| POST | `/api/ugc/comment/verify` | 审核评论 | Action\CommentController@verifyComment | jwt | api.auth,shoplog |
| GET | `/api/ugc/comment/list` | 获取评论列表 | Action\CommentController@getCommentList | jwt | activated |
| GET | `/api/ugc/comment/detail` | 笔记详情 | Action\CommentController@getCommentDetail | jwt | activated |
| POST | `/api/ugc/comment/delete` |  | Action\CommentController@deleteComment | jwt | api.auth,shoplog |
| POST | `/api/ugc/setting/point/saveSetting` | 保存积分设置 | Action\SettingController@savePointSetting | jwt | api.auth,shoplog |
| GET | `/api/ugc/setting/point/getSetting` | 获取积分设置 | Action\SettingController@getPointSetting | jwt | activated |

### `routes/api/weapp.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| GET | `/api/wxa/authorizer` | 获取授权小程序列表 | Action\Wxa@getWxaList | jwt | activated |
| GET | `/api/wxa/gettemplateweapplist` | 获取小程序模版列表 | Action\Wxa@getTemplateWeappList | jwt | activated |
| GET | `/api/wxa/gettemplateweappdetail` | 获取小程序模版详情 | Action\Wxa@getTemplateWeappDetail | jwt | activated |
| POST | `/api/wxa` | 上架小程序审核 | Action\Wxa@uploadWxa | jwt | activated |
| GET | `/api/wxa/codeunlimit` | 上传小程序码base64图片 | Action\Wxa@uploadWxaCodeUnlimit | jwt | activated |
| GET | `/api/wxa/testqrcode` | 小程序码体验二维码base64图片 | Action\Wxa@getTestQrcode | jwt | activated |
| POST | `/api/wxa/tryrelease` | 根据小程序审核状态尝试发布 | Action\Wxa@tryRelease | jwt | activated |
| GET | `/api/wxa/undocodeaudit` | 小程序审核撤回 | Action\Wxa@undocodeaudit | jwt | activated |
| GET | `/api/wxa/revertcoderelease` | 回退版本 | Action\Wxa@revertcoderelease | jwt | activated |
| POST | `/api/wxa/pageparams/setting` | 保存小程序页面单个挂件配置信息 | Action\Wxa@setPageParams | jwt | activated |
| GET | `/api/wxa/pageparams/setting` | 获取小程序页面配置信息 | Action\Wxa@getParamByTempName | jwt | activated |
| PUT | `/api/wxa/pageparams/setting` | 更新小程序页面单个配置信息 | Action\Wxa@updateParamsById | jwt | activated |
| POST | `/api/wxa/pageparams/setting_all` | 保存小程序页面配置信息 | Action\Wxa@savePageAllParams | jwt | activated |
| GET | `/api/wxa/{wxaAppId}` | 获取授权小程序详情 | Action\Wxa@getWxaDetail | jwt | activated |
| GET | `/api/wxa/templates/openlist` | 获取已有模版列表 | Action\Wxa@getOpenTemplateList | jwt | activated |
| GET | `/api/wxa/templates/list` | 获取模版列表 | Action\Wxa@getTemplateList | jwt | activated |
| POST | `/api/wxa/templates/open` | 开通模版 | Action\Wxa@openTemplate | jwt | activated |
| GET | `/api/wxa/templates/weappid` | 获取已有模版列表 | Action\Wxa@getWeappId | jwt | activated |
| POST | `/api/wxa/stats/summarybydate` | 某天概况趋势 | Action\WxappStats@getSummaryByDate | jwt | activated |
| POST | `/api/wxa/stats/summarytrend` | 小程序概况趋势 | Action\WxappStats@getSummaryTrend | jwt | activated |
| POST | `/api/wxa/stats/visitpage` | 小程序访问页面 | Action\WxappStats@getVisitPage | jwt | activated |
| POST | `/api/wxa/stats/visittrend` | 小程序访问趋势 | Action\WxappStats@getVisitTrend | jwt | activated |
| POST | `/api/wxa/stats/visitdistribution` | 小程序访问分布 | Action\WxappStats@getVisitDistribution | jwt | activated |
| POST | `/api/wxa/stats/retaininfo` | 小程序访问留存 | Action\WxappStats@getRetaininfo | jwt | activated |
| POST | `/api/wxa/stats/userportrait` | 小程序用户画像 | Action\WxappStats@getUserPortrait | jwt | activated |
| POST | `/api/wxa/customizepage` | 增加小程序自定义页面 | Action\CustomizePage@createCustomizePage | jwt | api.auth,shoplog |
| PUT | `/api/wxa/customizepage/{id}` | 更新小程序自定义页面 | Action\CustomizePage@updateCustomizePage | jwt | api.auth,shoplog |
| DELETE | `/api/wxa/customizepage/{id}` | 删除小程序自定义页面 | Action\CustomizePage@deleteCustomizePage | jwt | api.auth,shoplog |
| GET | `/api/wxa/customizepage/list` | 小程序自定义页面列表 | Action\CustomizePage@getCustomizepageList | jwt | api.auth,shoplog |
| GET | `/api/wxa/customizepage/{id}` | 获取小程序自定义页面详情 | Action\CustomizePage@getCustomizePageInfo | jwt | activated |
| GET | `/api/wxa/salesperson/customizepage` | 获取导购货架首页 | Action\CustomizePage@getSalespersonCustomizePage | jwt | api.auth,shoplog |
| PUT | `/api/wxa/customizepage/{id}/bindcategory` | 自定也页面绑定分类ID | Action\CustomizePage@bindCategoryId | jwt | api.auth,shoplog |
| POST | `/api/wxa/customizepage/copy/{id}` | 复制自定义页面 | Action\CustomizePage@copy | jwt | api.auth,shoplog |
| PUT | `/api/wxa/config/{wxaAppId}` | 小程序配置 | Action\Wxa@saveConfig | jwt | activated |
| PUT | `/api/wxappTemplate/wxapp` | 微信模板编辑 | Action\wxappTemplate@updateWxappTemplate | jwt | activated |
| PUT | `/api/wxappTemplate/domain` | 设置小程序需要用到的域名(全局) | Action\wxappTemplate@setDomain | jwt | activated |
| GET | `/api/wxappTemplate/domain` | 获取小程序需要用到的域名 | Action\wxappTemplate@getDomain | jwt | activated |
| POST | `/api/wxa/onlycode` | 仅上传小程序代码 | Action\Wxa@commitTempCode | jwt | activated |
| GET | `/api/wxa/config/{wxaAppId}` | 获取授权上架配置 | Action\Wxa@getConfigDetail | jwt | activated |
| POST | `/api/wxa/submitreview` | 提交小程序并审核 | Action\Wxa@submitReview | jwt | activated |
| POST | `/api/wxa/getdomainlist` | 获取小程序域名 | Action\Wxa@getDomainList | jwt | activated |
| POST | `/api/wxa/savedomain` | 保存小程序域名 | Action\Wxa@saveDomain | jwt | activated |
| POST | `/api/wxa/cartremind/setting` | 保存小程序购物车提醒配置 | Action\Wxa@setCartremindSetting | jwt | activated |
| GET | `/api/wxa/cartremind/setting` | 获取小程序购物车提醒配置 | Action\Wxa@getCartremindSetting | jwt | activated |
| GET | `/api/wxa/privacy/setting` | 查询小程序用户隐私保护指引 | Action\Wxa@getPrivacySetting | jwt | activated |
| POST | `/api/wxa/privacy/setting` | 设置小程序用户隐私保护指引 | Action\Wxa@setPrivacySetting | jwt | activated |
| POST | `/api/wxa/uploadprivacy/extfile` | 上传小程序用户隐私保护指引文件 | Action\Wxa@uploadPrivacyExtFile | jwt | activated |

### `routes/api/wechat.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| GET | `/api/wechat/pre_auth_url` | 获取微信公众号预授权URL地址 | Action\Authorized@getPreAuthUrl | jwt | activated |
| POST | `/api/wechat/bind` | 回调绑定当前授权信息 | Action\Authorized@authorizedBind | jwt | activated |
| POST | `/api/wechat/directbind` | 添加直连小程序 | Action\Authorized@directBind | jwt | activated |
| GET | `/api/wechat/authorizerinfo` | 获取公众帐号基础信息 | Action\Authorized@getAuthorizerInfo | jwt | activated |
| POST | `/api/wechat/menu` | 添加菜单 | Action\Menu@addMenu | jwt | activated |
| DELETE | `/api/wechat/menu` | 删除菜单 | Action\Menu@removeMenu | jwt | activated |
| GET | `/api/wechat/menutree` | 获取菜单树形列表 | Action\Menu@getMenuTree | jwt | activated |
| POST | `/api/wechat/kfs` | 添加微信客服 | Action\Kf@createWechatKf | jwt | activated |
| GET | `/api/wechat/kfs` | 获取所有客服账号列表 | Action\Kf@lists | jwt | activated |
| DELETE | `/api/wechat/kfs` | 删除指定微信客服 | Action\Kf@deleteWechatKf | jwt | activated |
| POST | `/api/wechat/update/kfs` | 修改指定微信客服 | Action\Kf@updateWechatKf | jwt | activated |
| POST | `/api/wechat/keyword/reply` | 新增关键字消息回复 | Action\MessageReply@addKeywordReply | jwt | activated |
| GET | `/api/wechat/keyword/reply` | 获取关键字回复列表 | Action\MessageReply@getKeywordReplyList | jwt | activated |
| PUT | `/api/wechat/keyword/reply` | 更新关键字消息回复 | Action\MessageReply@updateKeywordReply | jwt | activated |
| DELETE | `/api/wechat/keyword/reply` | 删除关键字回复规则 | Action\MessageReply@deleteKeywordReply | jwt | activated |
| POST | `/api/wechat/default/reply` | 设置默认回复 | Action\MessageReply@setDefaultReply | jwt | activated |
| GET | `/api/wechat/default/reply` | 获取默认消息回复 | Action\MessageReply@getDefaultReply | jwt | activated |
| GET | `/api/wechat/openkf/reply` | 获取多客服回复配置 | Action\MessageReply@getOpenKfReply | jwt | activated |
| POST | `/api/wechat/openkf/reply` | 设置多客服回复配置 | Action\MessageReply@setOpenKfReply | jwt | activated |
| GET | `/api/wechat/subscribe/reply` | 获取被关注自动回复消息配置 | Action\MessageReply@getSubscribeReply | jwt | activated |
| POST | `/api/wechat/subscribe/reply` | 设置被关注自动回复消息 | Action\MessageReply@setSubscribeReply | jwt | activated |
| POST | `/api/wechat/material` | 上传素材 | Action\Material@uploadMaterial | jwt | activated |
| POST | `/api/wechat/news/image` | 上传图文内的图片 | Action\Material@uploadArticleImage | jwt | activated |
| DELETE | `/api/wechat/material` | 删除素材 | Action\Material@deleteMaterial | jwt | activated |
| GET | `/api/wechat/material` | 获取永久素材列表 | Action\Material@getMaterialLists | jwt | activated |
| GET | `/api/wechat/material/stats` | 获取素材状态 | Action\Material@getMaterialStats | jwt | activated |
| POST | `/api/wechat/news` | 创建图文素材 | Action\Material@createNews | jwt | activated |
| GET | `/api/wechat/news/{materialId}` | 获取图文素材详情 | Action\Material@getNewsMaterial | jwt | activated |
| PUT | `/api/wechat/news` | 修改图文素材 | Action\Material@updateArticle | jwt | activated |
| GET | `/api/wechat/stats/userweeksummary` | 最近七天用户数据统 | Action\Stats@userWeekSummary | jwt | activated |
| POST | `/api/wechat/open` | 开通开放平台账号并且绑定小程序 | Action\Open@openCreate | jwt | activated |
| GET | `/api/wechat/offiaccountcodeforever` | 获取公众号永久二维码 | Action\Wxa@getOffiaccountCodeForever | jwt | activated |
| GET | `/api/workwechat/config` | 获取企业微信配置 | Action\WorkWechat@getConfig | jwt | activated |
| GET | `/api/workwechat/report` | 获取企业微信通讯录 | Action\WorkWechat@getReport | jwt | activated |
| GET | `/api/workwechat/report/{department_id}` | 获取企业微信部门成员列表 | Action\WorkWechat@getReportUserLists | jwt | activated |
| POST | `/api/workwechat/config` | 保存企业微信配置 | Action\WorkWechat@setConfig | jwt | activated |
| POST | `/api/workwechat/report/syncDistributor` | 同步企微部门信息到店铺 | Action\WorkWechat@syncDistributor | jwt | activated |
| POST | `/api/workwechat/report/syncSalesperson` | 同步企微部门成员到导购员 | Action\WorkWechat@syncSalesperson | jwt | activated |
| GET | `/api/workwechat/rellist/{salespersonId}` | 导购员企业微信关联信息 | Action\WorkWechat@getWorkWechatList | jwt | activated |
| GET | `/api/workwechat/rellogs/{userId}` | 导购员企业微信关联信息日志 | Action\WorkWechat@getWorkWechatLogsList | jwt | activated |
| GET | `/api/workwechat/messagetemplate` | 企业微信通知模板列表获取 | Action\WorkWechatMessageTemplate@getTemplateList | jwt | activated |
| GET | `/api/workwechat/messagetemplate/{templateId}` | 企业微信通知模板获取 | Action\WorkWechatMessageTemplate@getTemplate | jwt | activated |
| PUT | `/api/workwechat/messagetemplate/{templateId}` | 企业微信通知模板保存 | Action\WorkWechatMessageTemplate@saveTemplate | jwt | activated |
| PUT | `/api/workwechat/messagetemplate/open/{templateId}` | 企业微信通知模板保存 | Action\WorkWechatMessageTemplate@openTemplate | jwt | activated |
| PUT | `/api/workwechat/messagetemplate/close/{templateId}` | 企业微信通知模板保存 | Action\WorkWechatMessageTemplate@closeTemplate | jwt | activated |
| POST | `/api/workwechat/distributor/js/config` | 前台获取JsSDK | Action\WorkWechat@getDistributorJsConfig | jwt | activated |
| POST | `/api/workwechat/domain/verify` | 上传企业微信校验域名文件 | Action\WorkWechat@verifyDomain | jwt | activated |
| ANY | `/api/workwechat/notify/{corpid}` | 企业微信回调 | Controllers\WorkWechatCallback@notify |  | activated |
| ANY | `/api/workwechat/customer/notify/{corpid}` | 企业微信客户联系回调 | Controllers\WorkWechatCallback@customerNotify |  | activated |
| ANY | `/api/workwechat/report/notify/{corpid}` | 企业微信通讯录回调 | Controllers\WorkWechatCallback@reportNotify |  | activated |

## B. 店铺员工小程序端 API（114 条）

> 基址 `/api/admin/wxapp/…`，provider `adminwxapp`（导购 x-wxapp-session）


### `routes/admin/aftersales.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| GET | `/api/admin/wxapp/aftersales` | 获取售后列表 | Action\Aftersales@getAftersalesList | adminwxapp | api.auth,distributorlog |
| GET | `/api/admin/wxapp/aftersales/info` | 获取售后详情 | Action\Aftersales@getAftersalesDetail | adminwxapp | api.auth,distributorlog |
| POST | `/api/admin/wxapp/aftersales/review` | 售后审核 | Action\Aftersales@aftersalesReview | adminwxapp | api.auth,distributorlog |
| POST | `/api/admin/wxapp/aftersales/refundCheck` | 售后退款审核 | Action\Aftersales@refundCheck | adminwxapp | api.auth,distributorlog |
| POST | `/api/admin/wxapp/aftersales` | 自提订单售后申请 | Action\Aftersales@apply | adminwxapp | api.auth,distributorlog |
| GET | `/api/admin/wxapp/aftersales/reason/list` | 获取售后原因列表 | Action\Reason@getSreasonList | adminwxapp | api.auth,distributorlog |

### `routes/admin/auth.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| POST | `/api/admin/wxapp/login` |  | Action\Wxapp@login |  |  |
| POST | `/api/admin/wxapp/workwecahtlogin` |  | Action\Wxapp@workwechatlogin |  |  |
| POST | `/api/admin/wxapp/check` |  | Action\Wxapp@checkSessionKey |  |  |
| GET | `/api/admin/wxapp/qrcode` |  | Action\Qrcode@getQrcode |  |  |
| POST | `/api/admin/wxapp/espier/image_upload_token` |  | Action\UploadFile@getPicUploadToken |  |  |
| POST | `/api/admin/wxapp/espier/uploadlocal` |  | Action\UploadFile@uploadeLocalImage |  |  |

### `routes/admin/card.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| GET | `/api/admin/wxapp/card_consume` | 卡券扫码核销 | Action\UserDiscount@userCardConsume | adminwxapp | api.auth,distributorlog |
| GET | `/api/admin/wxapp/user_card_detail` | 卡券核销获取 | Action\UserDiscount@getUserCardDetail | adminwxapp | api.auth,distributorlog |
| POST | `/api/admin/wxapp/salespersongivecoupons` | 导购员发放优惠券给会员 | Action\UserDiscount@giveUserCoupons | adminwxapp | api.auth,distributorlog |
| POST | `/api/admin/wxapp/salespersongivecoupons/{id}` | 导购员重试发放优惠券给会员 | Action\UserDiscount@tryGiveUserCoupons | adminwxapp | api.auth,distributorlog |
| GET | `/api/admin/wxapp/permissioncoupons` | 获取导购员可发放优惠券列表 | Action\UserDiscount@getPermissionCouponsList | adminwxapp | api.auth,distributorlog |
| GET | `/api/admin/wxapp/getusercoupons` | 导购员获取用户已领取的优惠券列表 | Action\UserDiscount@getUserCouponsList | adminwxapp | api.auth,distributorlog |
| GET | `/api/admin/wxapp/couponrecord` | 导购员获取赠券记录 | Action\UserDiscount@getCouponsRecord | adminwxapp | api.auth,distributorlog |
| GET | `/api/admin/wxapp/sendCouponList` | 导购员赠券记录 | Action\UserDiscount@getSendCouponsList | adminwxapp | api.auth,distributorlog |
| GET | `/api/admin/wxapp/salesperson/coupon` | 获取导购员可发放优惠券列表 | Action\SalespersonCouponController@lists | adminwxapp | api.auth,distributorlog |
| GET | `/api/admin/wxapp/couponrecord` | 导购员获取赠券记录 | Action\UserDiscount@getCouponsRecord | adminwxapp | api.auth,distributorlog |

### `routes/admin/distributor.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| GET | `/api/admin/wxapp/getinfo` | 获取导购自己的信息 | Action\ShopSalespersonController@getSelfInfo | adminwxapp | api.auth,distributorlog |
| GET | `/api/admin/wxapp/distributorlist` | 获取导购店铺列表 | Action\ShopSalespersonController@getDistributorDataList | adminwxapp | api.auth,distributorlog |
| GET | `/api/admin/wxapp/shoplist` | 获取门店列表 | Action\ShopSalespersonController@getShopDataList | adminwxapp | api.auth,distributorlog |
| GET | `/api/admin/wxapp/salespersonCount` | 获取导购数据统计 | Action\ShopSalespersonController@getSalespersonCountData | adminwxapp | api.auth,distributorlog |
| GET | `/api/admin/wxapp/salespersonQrcode` | 获取导购二维码获取 | Action\ShopSalespersonController@getSalespersonContactQrCode | adminwxapp | api.auth,distributorlog |
| POST | `/api/admin/wxapp/salespersonQrcode` | 导购二维码更新 | Action\ShopSalespersonController@updateSalespersonContactQrCode | adminwxapp | api.auth,distributorlog |
| GET | `/api/admin/wxapp/cartdataadd` | 导购员购物车新增 | Action\SalespersonCartController@cartdataAdd | adminwxapp | api.auth,distributorlog |
| POST | `/api/admin/wxapp/items/scancodeAddcart` | 扫条形码加入购物车 | Action\SalespersonCartController@scanCodeSales | adminwxapp | api.auth,distributorlog |
| POST | `/api/admin/wxapp/scancodeAddcart` | 扫条形码加入购物车 | Action\SalespersonCartController@scanCodeSales | adminwxapp | api.auth,distributorlog |
| GET | `/api/admin/wxapp/cartdataupdate` | 导购员购物车更新 | Action\SalespersonCartController@updateCartdata | adminwxapp | api.auth,distributorlog |
| GET | `/api/admin/wxapp/cartdatalist` | 获取导购员购物车 | Action\SalespersonCartController@getCartdataList | adminwxapp | api.auth,distributorlog |
| GET | `/api/admin/wxapp/cartdatadel` | 导购员购物车删除 | Action\SalespersonCartController@delCartdata | adminwxapp | api.auth,distributorlog |
| GET | `/api/admin/wxapp/salesPromotion` | 获取导购员促销单 | Action\SalespersonCartController@createSalesPromotion | adminwxapp | api.auth,distributorlog |
| GET | `/api/admin/wxapp/statistics` | 获取导购统计(旧) | Action\StatisticsController@lists | adminwxapp | api.auth,distributorlog |
| GET | `/api/admin/wxapp/statistics/typelist` | 获取导购统计类型(旧) | Action\StatisticsController@typeList | adminwxapp | api.auth,distributorlog |
| GET | `/api/admin/wxapp/noticeunreadcount` | 获取导购员未读消息数量 | Action\SalespersonNoticeController@getUnreadNum | adminwxapp | api.auth,distributorlog |
| GET | `/api/admin/wxapp/noticelist` | 获取导购员通知列表 | Action\SalespersonNoticeController@getNoticeList | adminwxapp | api.auth,distributorlog |
| GET | `/api/admin/wxapp/notice` | 获取导购员通知详情并设为已读 | Action\SalespersonNoticeController@getNoticeDetail | adminwxapp | api.auth,distributorlog |
| GET | `/api/admin/wxapp/leaderboard` | 获取导购端首页排名 | Action\SalespersonLeaderboardController@getLeaderboardInfo | adminwxapp | api.auth,distributorlog |
| GET | `/api/admin/wxapp/leaderboard/salesperson` | 获取导购排名列表 | Action\SalespersonLeaderboardController@getSalespersonLeaderboardList | adminwxapp | api.auth,distributorlog |
| GET | `/api/admin/wxapp/leaderboard/distributor` | 获取店铺排名列表 | Action\SalespersonLeaderboardController@getDistributorLeaderboardList | adminwxapp | api.auth,distributorlog |
| GET | `/api/admin/wxapp/salesperson/task` | 获取导购任务列表 | Action\SalespersonTaskController@lists | adminwxapp | api.auth,distributorlog |
| GET | `/api/admin/wxapp/salesperson/task/{taskId}` | 获取导购任务详情 | Action\SalespersonTaskController@info | adminwxapp | api.auth,distributorlog |
| POST | `/api/admin/wxapp/shop/checkSign` |  | Action\ShopSalespersonController@checkSign | adminwxapp | api.auth,distributorlog |
| POST | `/api/admin/wxapp/shop/signin` |  | Action\ShopSalespersonController@signin | adminwxapp | api.auth,distributorlog |
| POST | `/api/admin/wxapp/shop/signout` |  | Action\ShopSalespersonController@signout | adminwxapp | api.auth,distributorlog |
| GET | `/api/admin/wxapp/bydistributor/salespersonQrcode/{company_id}` | 根据门店获取导购二维码 | Action\ShopSalespersonController@getSalespersonContactQrCodeByDistributor | adminwxapp | api.auth,distributorlog |
| GET | `/api/h5app/wxapp/bydistributor/salespersonQrcode/{company_id}` | 根据门店获取导购二维码 | Action\ShopSalespersonController@getSalespersonContactQrCodeByDistributor | adminwxapp | api.auth,distributorlog |
| GET | `/api/admin/wxapp/salespersonCount` | 获取导购当月统计信息 | Action\ShopSalespersonController@getSalespersonCountData | adminwxapp | api.auth,distributorlog |
| GET | `/api/admin/wxapp/salespersonFee` | 统计导购分润信息 | Action\ShopSalespersonController@getSalespersonProfitFee | adminwxapp | api.auth,distributorlog |

### `routes/admin/goods.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| GET | `/api/admin/wxapp/items/list` | 获取商品列表 | Action\Items@getItemsList | adminwxapp | api.auth,distributorlog |
| GET | `/api/admin/wxapp/goods/promotion/items` | 获取商品列表 | Action\Items@getPromotionItemList | adminwxapp | api.auth,distributorlog |
| GET | `/api/admin/wxapp/goods/itemsinfo` | 获取商品详情 | Action\Items@getItemsDetail | adminwxapp | api.auth,distributorlog |
| GET | `/api/admin/wxapp/goods/category` | 获取分类列表 | Action\Category@getCategoryList | adminwxapp | api.auth,distributorlog |
| GET | `/api/admin/wxapp/custom/goods/category` | 获取自定义分类列表 | Action\Category@getCustomCategoryList | adminwxapp | api.auth,distributorlog |
| GET | `/api/admin/wxapp/goods/category/{cat_id}` | 获取分类子分类 | Action\Category@getChildrenCategorys | adminwxapp | api.auth,distributorlog |
| GET | `/api/admin/wxapp/goods/categorylevel` | 获取指定等级分类列表 | Action\Category@getLevelCategoryList | adminwxapp | api.auth,distributorlog |

### `routes/admin/member.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| GET | `/api/admin/wxapp/getUserData` | 根据手机号获取用户信息 | Action\UserData@getUserData | adminwxapp | api.auth,distributorlog |
| GET | `/api/admin/wxapp/asyncGetUserData` | 根据手机号获取用户信息(异步) | Action\UserData@asyncGetUserData | adminwxapp | api.auth,distributorlog |
| GET | `/api/admin/wxapp/getUserList` | 获取指定导购下关联的会员列表 | Action\UserData@getUserList | adminwxapp | api.auth,distributorlog |
| GET | `/api/admin/wxapp/distributors/getUserList` | 获取指定店铺下关联的会员列表 | Action\UserData@getDistributorUserList | adminwxapp | api.auth,distributorlog |
| GET | `/api/admin/wxapp/member/taglist` | 获取会员标签列表 | Action\UserData@getTagsList | adminwxapp | api.auth,distributorlog |
| POST | `/api/admin/wxapp/member/reltag` | 关联会员标签 | Action\UserData@userRelTag | adminwxapp | api.auth,distributorlog |
| DELETE | `/api/admin/wxapp/member/delreltag` | 删除会员标签 | Action\UserData@delRelUserTag | adminwxapp | api.auth,distributorlog |
| GET | `/api/admin/wxapp/member/onlinetag` | 获取会员标签列表 | Action\UserTagController@getOnlineUserTags | adminwxapp | api.auth,distributorlog |
| POST | `/api/admin/wxapp/member/tagadd` | 会员标签添加 | Action\UserTagController@addUserTag | adminwxapp | api.auth,distributorlog |
| POST | `/api/admin/wxapp/member/tagupdate` | 会员标签编辑 | Action\UserTagController@updateUserTag | adminwxapp | api.auth,distributorlog |
| GET | `/api/admin/wxapp/member/selftag` | 获取管理员自有会员标签 | Action\UserTagController@getSelfUserTags | adminwxapp | api.auth,distributorlog |
| DELETE | `/api/admin/wxapp/member/delselftag` | 删除管理员自有会员标签 | Action\UserTagController@delSelfUserTags | adminwxapp | api.auth,distributorlog |
| POST | `/api/admin/wxapp/member/grouplist` | 导购员创建会员分组 | Action\UserGroupController@createGroup | adminwxapp | api.auth,distributorlog |
| GET | `/api/admin/wxapp/member/grouplist` | 导购员获取会员分组列表 | Action\UserGroupController@getGroupList | adminwxapp | api.auth,distributorlog |
| GET | `/api/admin/wxapp/member/userlistbygroup` | 导购员根据分组id获取会员列表 | Action\UserGroupController@getUsersByGroup | adminwxapp | api.auth,distributorlog |
| PUT | `/api/admin/wxapp/member/grouplist` | 导购员修改会员分组列表 | Action\UserGroupController@updateGroup | adminwxapp | api.auth,distributorlog |
| POST | `/api/admin/wxapp/member/moveusertogroup` | 导购员移动会员到分组 | Action\UserGroupController@moveUserToGroup | adminwxapp | api.auth,distributorlog |
| DELETE | `/api/admin/wxapp/member/grouplist` | 导购员删除分组 | Action\UserGroupController@deleteGroup | adminwxapp | api.auth,distributorlog |
| POST | `/api/admin/wxapp/member/remarks` | 导购员备注会员 | Action\UserRemarksController@addRemarks | adminwxapp | api.auth,distributorlog |
| GET | `/api/admin/wxapp/member/remarks` | 获取导购员备注会员 | Action\UserRemarksController@getRemarks | adminwxapp | api.auth,distributorlog |
| GET | `/api/admin/wxapp/member/browse/history/{userId}` | 获取历史浏览记录 | Action\UserData@getBrowseHistory | adminwxapp | api.auth,distributorlog |

### `routes/admin/orders.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| POST | `/api/admin/wxapp/order/create` | 导购员创建订单 | Action\Orders@createOrders | adminwxapp | api.auth,distributorlog |
| GET | `/api/admin/wxapp/order/getlist` | 导购员获取用户订单列表 | Action\Orders@getOrdersList | adminwxapp | api.auth,distributorlog |
| GET | `/api/admin/wxapp/order/getinfo` | 导购员获取用户订单详情 | Action\Orders@getOrdersInfo | adminwxapp | api.auth,distributorlog |
| GET | `/api/admin/wxapp/order/getsalespersonlist` | 获取导购分润订单列表 | Action\Orders@getSalespersonOrderList | adminwxapp | api.auth,distributorlog |
| GET | `/api/admin/wxapp/order/getSalepersonOrdersList` | 获取店铺订单列表 | Action\Orders@getSalespersonOrdersList | adminwxapp | api.auth,distributorlog |
| POST | `/api/admin/wxapp/order/delivery` | 导购员订单发货 | Action\Orders@delivery | adminwxapp | api.auth,distributorlog |
| POST | `/api/admin/wxapp/order/process/{orderId}` | 导购员订单发货 | Action\Orders@getOrderProcessLog | adminwxapp | api.auth,distributorlog |
| GET | `/api/admin/wxapp/trackerpull` | 订单物流查询 | Action\Orders@trackerpull | adminwxapp | api.auth,distributorlog |
| POST | `/api/admin/wxapp/order/ziti` | 订单自提核销 | Action\NormalOrder@finishOrderZiti | adminwxapp | api.auth,distributorlog |
| GET | `/api/admin/wxapp/order/detail` | 订单详情 | Action\NormalOrder@getOrderDetail | adminwxapp | api.auth,distributorlog |
| POST | `/api/admin/wxapp/normalcreate` | 订单创建 | Action\NormalOrder@createUserOrder | adminwxapp | api.auth,distributorlog |
| GET | `/api/admin/wxapp/cartcheckout` | 购物车结算列表 | Action\NormalOrder@cartCheckout | adminwxapp | api.auth,distributorlog |
| GET | `/api/admin/wxapp/orderstatus` | 获取订单支付状态 | Action\NormalOrder@getPayStatus | adminwxapp | api.auth,distributorlog |
| GET | `/api/admin/wxapp/logistics/list` | 获取启用物流公司列表 | Action\CompanyRelLogistics@getCompanyLogisticsList | adminwxapp | api.auth,distributorlog |

### `routes/admin/promotions.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| GET | `/api/admin/wxapp/promotions/activearticlelist` | 获取活动文章列表 | Action\ActiveArticle@getActiveArticleList | adminwxapp | api.auth,distributorlog |
| GET | `/api/admin/wxapp/promotions/activearticle/{id}` | 获取活动文章详情 | Action\ActiveArticle@getActiveArticleDetail | adminwxapp | api.auth,distributorlog |
| GET | `/api/admin/wxapp/promotions/activearticleforward` | 转发活动 | Action\ActiveArticle@forwardActiveArticle | adminwxapp | api.auth,distributorlog |

### `routes/admin/reservation.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| GET | `/api/admin/wxapp/reservation/list` | 获取每天每时每刻的预约记录 | Action\Reservation@getReservationList | adminwxapp | api.auth,distributorlog |
| GET | `/api/admin/wxapp/reservation/getdate` | 获取可预约的日期列表及资源位列表 | Action\Reservation@getDateList | adminwxapp | api.auth,distributorlog |
| GET | `/api/admin/wxapp/resourcelevel/list` | 获取指定门店的所有资源位 | Action\Reservation@getResourceLevelList | adminwxapp | api.auth,distributorlog |
| POST | `/api/admin/wxapp/reservation` | 提交预约信息 | Action\Reservation@createReservation | adminwxapp | api.auth,distributorlog |
| GET | `/api/admin/wxapp/getRightsList` | 获取可被预约的项目 | Action\Reservation@getUserRightsListData | adminwxapp | api.auth,distributorlog |
| GET | `/api/admin/wxapp/getTimeList` | 获取可被预约的时段 | Action\Reservation@getTimeList | adminwxapp | api.auth,distributorlog |
| POST | `/api/admin/wxapp/reservation/updateStatus` | 修改预约状态 | Action\Reservation@updateRecordStatus | adminwxapp | api.auth,distributorlog |

### `routes/admin/selfService.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| GET | `/api/admin/wxapp/selfform/list` | 获取表单模板列表 | Action\FormTemplateController@getDatalist | adminwxapp | api.auth,distributorlog |
| GET | `/api/admin/wxapp/selfform/tempinfo` | 获取指定模板 | Action\FormTemplateController@getTemplateInfo | adminwxapp | api.auth,distributorlog |
| POST | `/api/admin/wxapp/selfform/saveuserform` | 保存自助表单内容 | Action\FormTemplateController@saveSelfFormData | adminwxapp | api.auth,distributorlog |
| GET | `/api/admin/wxapp/selfform/statisticalAnalysis` | 获取指定时间段内的数据统计数据 | Action\FormTemplateController@statisticalAnalysis | adminwxapp | api.auth,distributorlog |
| GET | `/api/admin/wxapp/selfform/physical/datelist` | 获取所有记录的日期列表 | Action\FormTemplateController@getRecordDateList | adminwxapp | api.auth,distributorlog |

### `routes/admin/trade.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| GET | `/api/admin/wxapp/trade` | 获取交易列表 | Action\Trade@getTradelist | adminwxapp | api.auth,distributorlog |
| GET | `/api/admin/wxapp/right` | 获取权益详情 | Action\Rights@getRightsDetail | adminwxapp | api.auth,distributorlog |
| POST | `/api/admin/wxapp/right/consume` | 核销权益 | Action\Rights@consumeRights | adminwxapp | api.auth,distributorlog |
| GET | `/api/admin/wxapp/right/list` | 获取指定会员权益列表 | Action\Rights@getRightsList | adminwxapp | api.auth,distributorlog |
| GET | `/api/admin/wxapp/consumer/list` | 获取会员核销记录列表 | Action\Rights@getRightsConsumerList | adminwxapp | api.auth,distributorlog |

## C. 消费者端 API（H5/小程序）（579 条）

> 基址 `/api/h5app/…` 或 `/api/wxapp/…`，provider `wxapp`/`jwt`/公开


### `routes/frontapi/adapay.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| GET | `/api/h5app/wxapp/adapay/popularize/cert` | 获取分销员认证信息 | Action\AdapayPromoter@getCertInfo | jwt | dingoguard:h5app,api.auth |
| POST | `/api/h5app/wxapp/adapay/popularize/create_cert` | 新建分销员认证信息 | Action\AdapayPromoter@createCert | jwt | dingoguard:h5app,api.auth |
| POST | `/api/h5app/wxapp/adapay/popularize/update_cert` | 编辑分销员认证信息 | Action\AdapayPromoter@updateCert | jwt | dingoguard:h5app,api.auth |
| POST | `/api/h5app/wxapp/adapay/bank/list` | 获取银行列表 | Action\Account@getBanksLists | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/adapay/bank/list` | 获取银行列表 | Action\Account@getBanksLists | jwt | frontnoauth:h5app |

### `routes/frontapi/aftersales.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| POST | `/api/h5app/wxapp/aftersales` | 订单售后申请 | Action\Aftersales@apply | jwt | dingoguard:h5app,api.auth |
| POST | `/api/h5app/wxapp/aftersales/modify` | 编辑售后单 | Action\Aftersales@modify | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/aftersales` | 获取售后列表 | Action\Aftersales@getAftersalesList | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/aftersales/info` | 获取售后单详情 | Action\Aftersales@getAftersalesDetail | jwt | dingoguard:h5app,api.auth |
| POST | `/api/h5app/wxapp/aftersales/sendback` | 售后消费者回寄 | Action\Aftersales@sendback | jwt | dingoguard:h5app,api.auth |
| POST | `/api/h5app/wxapp/aftersales/close` | 售后关闭 | Action\Aftersales@closeConfirm | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/aftersales/item/price` | 获取售后商品价格 | Action\Aftersales@getRefundAmount | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/aftersales/reason/list` | 获取售后原因列表 | Action\Reason@getSreasonList | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/aftersales/remind/detail` | 售后提醒内容获取 | Action\Aftersales@getRemind | jwt | dingoguard:h5app,api.auth |

### `routes/frontapi/auth.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| GET | `/api/h5app/wxapp/espier/image_upload_token` |  | Action\UploadFile@getPicUploadToken |  | dingoguard:h5app,api.auth |
| POST | `/api/h5app/wxapp/espier/image_upload` |  | Action\UploadFile@uploadOssImage |  | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/espier/address` |  | Action\AddressController@get |  | frontnoauth:h5app |
| POST | `/api/h5app/wxapp/espier/upload` |  | Action\UploadFile@uploadImage |  | dingoguard:h5app,api.auth |
| POST | `/api/h5app/wxapp/espier/uploadlocal` |  | Action\UploadFile@uploadeLocalImage |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/espier/config/request_field_setting` |  | Action\ConfigRequestFieldsController@getConfig |  | frontnoauth:h5app |
| POST | `/api/h5app/wxapp/oauthlogin` |  | Action\Wxapp@checkOauthLogin |  |  |
| POST | `/api/h5app/wxapp/getopenid` |  | Action\Wxapp@getUserOpentIdAndUnionid |  |  |
| POST | `/api/h5app/wxapp/new_login` |  | EspierBundle\Http\FrontApi\V1\Action\LoginController@login |  | frontnoauth:h5app |
| POST | `/api/h5app/wxapp/login` |  | closure |  |  |
| GET | `/api/wxapp/token/refresh` |  | closure |  | jwt.refresh |
| POST | `/api/wxapp/merchant/login` |  | closure |  |  |
| GET | `/api/h5app/wxapp/oauth/getredirecturl` |  | Action\Wxapp@oauthRedirectUrl |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/oauth/getopenid` |  | Action\Wxapp@getOpenId |  | frontnoauth:h5app |
| POST | `/api/h5app/wxapp/oauth/login/authorize` |  | Action\Wxapp@authorizeOauthLogin |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/oauth/login/valid` |  | Action\Wxapp@validOauthLogin |  | frontnoauth:h5app |
| POST | `/api/h5app/wxapp/workwechatlogin` |  | Action\Wxapp@workwechatlogin |  | frontnoauth:h5app |

### `routes/frontapi/card.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| GET | `/api/h5app/wxapp/getCardDetail/{cardId}` |  | Action\DiscountCard@getDiscountCardDetail | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/user/receiveCard` |  | Action\UserDiscount@receiveCard | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/user/consumCard` |  | Action\UserDiscount@ConsumCard | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/user/removeCard` |  | Action\UserDiscount@DeleteUserCard | jwt | dingoguard:h5app,api.auth |
| POST | `/api/h5app/wxapp/user/exchangeCard` |  | Action\UserDiscount@exchangeCard | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/user/exchangeCardInfo` |  | Action\UserDiscount@exchangeCardInfo | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/user/getCardList` |  | Action\UserDiscount@getUserCardList | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/user/getCardDetail` |  | Action\UserDiscount@getUserDiscountDetail | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/user/usedCard` |  | Action\UserDiscount@userUsedCard | jwt | dingoguard:h5app,api.auth |
| POST | `/api/h5app/wxapp/user/currentGardCardPackage` |  | Action\UserDiscount@currentGardCardPackage | jwt | dingoguard:h5app,api.auth |
| POST | `/api/h5app/wxapp/user/receiveCardPackage` |  | Action\UserDiscount@receivesPackage | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/user/showCardPackage` |  | Action\UserDiscount@showCardPackage | jwt | dingoguard:h5app,api.auth |
| POST | `/api/h5app/wxapp/user/confirmPackageShow` |  | Action\UserDiscount@confirmPackageReceivesShow | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/user/getBindCardList` |  | Action\UserDiscount@getCardListByBindType | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/vipgrades/uservip` |  | Action\VipGradeController@getUserVipGrade | jwt | dingoguard:h5app,api.auth |
| POST | `/api/h5app/wxapp/vipgrades/buy` |  | Action\VipGradeController@buyDataVipGrade | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/user/newGetCardList` |  | Action\UserDiscount@getUserCardList | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/user/newGetCardDetail` |  | Action\UserDiscount@getUserDiscountDetail | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/user/getUserCardList` |  | Action\UserDiscount@getMyUserCardList | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/getCardList` |  | Action\DiscountCard@getDiscountCardList |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/vipgrades/list` |  | Action\VipGradeController@listDataVipGrade |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/membercard/grades` |  | Action\VipGradeController@getGradeList |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/vipgrades/newlist` |  | Action\VipGradeController@listDataVipGrade |  | frontnoauth:h5app |

### `routes/frontapi/comments.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| POST | `/api/h5app/wxapp/comment` |  | Action\Comments@createComment | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/comments` |  | Action\Comments@getComments | jwt | dingoguard:h5app,api.auth |

### `routes/frontapi/community.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| GET | `/api/h5app/wxapp/community/chief/aggrement_and_explanation` |  | chief\CommunityChief@getAggrementAndExplanation |  | dingoguard:h5app |
| GET | `/api/h5app/wxapp/community/chief/apply_fields` |  | chief\CommunityChief@getApplyFields |  | dingoguard:h5app |
| POST | `/api/h5app/wxapp/community/chief/apply` |  | chief\CommunityChief@apply |  | dingoguard:h5app |
| GET | `/api/h5app/wxapp/community/chief/apply` |  | chief\CommunityChief@getApplyInfo |  | dingoguard:h5app |
| POST | `/api/h5app/wxapp/community/checkChief` |  | chief\CommunityChief@checkChief |  | dingoguard:h5app |
| POST | `/api/h5app/wxapp/community/chief/cash_withdrawal` |  | chief\CommunityChiefCashWithdrawal@applyCashWithdrawal |  | dingoguard:h5app |
| GET | `/api/h5app/wxapp/community/chief/cash_withdrawal` |  | chief\CommunityChiefCashWithdrawal@getCashWithdrawalList |  | dingoguard:h5app |
| GET | `/api/h5app/wxapp/community/chief/cash_withdrawal/account` |  | chief\CommunityChiefCashWithdrawal@getCashWithdrawalAccount |  | dingoguard:h5app |
| POST | `/api/h5app/wxapp/community/chief/cash_withdrawal/account` |  | chief\CommunityChiefCashWithdrawal@updateCashWithdrawalAccount |  | dingoguard:h5app |
| GET | `/api/h5app/wxapp/community/chief/cash_withdrawal/count` |  | chief\CommunityChiefCashWithdrawal@cashWithdrawalCount |  | dingoguard:h5app |
| GET | `/api/h5app/wxapp/community/activity/lists` |  | chief\CommunityActivity@getActivityList |  | dingoguard:h5app |
| GET | `/api/h5app/wxapp/community/chief/activity/{activity_id}` |  | chief\CommunityActivity@getActivityDetail |  | dingoguard:h5app |
| GET | `/api/h5app/wxapp/community/orders` | 获取订单列表 | chief\CommunityOrder@getOrderList |  | dingoguard:h5app |
| GET | `/api/h5app/wxapp/community/orders/export` | 导出团购订单 | chief\CommunityOrder@exportActivityOrderData |  | dingoguard:h5app |
| POST | `/api/h5app/wxapp/community/orders/batch_writeoff` | 团购订单一键核销 | chief\CommunityOrder@batchWriteoff |  | dingoguard:h5app |
| POST | `/api/h5app/wxapp/community/orders/qr_writeoff` | 团购订单扫码核销 | chief\CommunityOrder@writeoffQR |  | dingoguard:h5app |
| POST | `/api/h5app/writeoff/{order_id}` | 团购订单自助核销 | chief\CommunityOrder@writeoff |  | dingoguard:h5app |
| GET | `/api/h5app/wxapp/community/chief/distributor` |  | chief\CommunityChief@getDisitrbutorList |  | dingoguard:h5app |
| GET | `/api/h5app/wxapp/community/chief/items` |  | chief\CommunityActivity@getDisitrbutorItemList |  | dingoguard:h5app |
| GET | `/api/h5app/wxapp/community/chief/ziti` |  | chief\CommunityChiefZiti@actionList |  | dingoguard:h5app |
| POST | `/api/h5app/wxapp/community/chief/ziti` |  | chief\CommunityChiefZiti@actionCreate |  | dingoguard:h5app |
| POST | `/api/h5app/wxapp/community/chief/ziti/{ziti_id}` |  | chief\CommunityChiefZiti@actionUpdate |  | dingoguard:h5app |
| GET | `/api/h5app/wxapp/community/chief/activity` |  | chief\CommunityActivity@getActivityList |  | dingoguard:h5app |
| POST | `/api/h5app/wxapp/community/chief/activity` |  | chief\CommunityActivity@createActivity |  | dingoguard:h5app |
| POST | `/api/h5app/wxapp/community/chief/activity/{activity_id}` |  | chief\CommunityActivity@updateActivity |  | dingoguard:h5app |
| POST | `/api/h5app/wxapp/community/chief/activity_status/{activity_id}` |  | chief\CommunityActivity@updateActivityStatus |  | dingoguard:h5app |
| POST | `/api/h5app/wxapp/community/chief/confirm_delivery/{activity_id}` |  | chief\CommunityActivity@confirmDeliveryStatus |  | dingoguard:h5app |
| GET | `/api/h5app/wxapp/community/member/activity` |  | member\CommunityActivity@getActivityList | jwt | dingoguard:h5app |
| GET | `/api/h5app/wxapp/community/member/activity/{activity_id}` |  | member\CommunityActivity@getActivityDetail | jwt | dingoguard:h5app |
| GET | `/api/h5app/wxapp/community/member/items` |  | member\CommunityActivity@getDisitrbutorItemList | jwt | dingoguard:h5app |

### `routes/frontapi/companys.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| GET | `/api/h5app/wxapp/shops/wxshops` | 获取微信门店列表 | Action\Shops@getWxShopsList |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/shops/wxshops/{wx_shop_id}` | 获取单个微信门店详情 | Action\Shops@getWxShopsDetail |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/shops/getNearestWxShops` | 获取最近门店 | Action\Shops@getNearestWxShops |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/shops/info` | 获取站点的基本信息 | Action\Shops@getBaseInfo |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/shops/protocol` | 获取站点的协议信息 | Action\ProtocolController@get |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/shops/protocolUpdateTime` | 获取站点的协议发布时间 | Action\ProtocolController@getUpdateTime |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/shops/protocolsaleman` | 获取业务员协议信息 | Action\ProtocolController@protocolsaleman |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/article/management` | 文章列表 | Action\ArticleController@listDataArticle |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/article/management/{article_id}` | 文章详情 | Action\ArticleController@infoDataArticle |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/article/focus/{article_id}` | 文章关注 | Action\ArticleController@articleFocus |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/article/focus/num/{article_id}` | 文章关注总数量 | Action\ArticleController@ArticleFocusNum |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/article/praise/num/{article_id}` | 文章点赞总数 | Action\ArticleController@articlePraiseNum |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/article/category` | 获取文章栏目列表 | Action\ArticleController@getCategory |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/article/province` | 获取文章的所有省份列表 | Action\ArticleController@getAllProvince |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/article/praise/{article_id}` | 文章点赞 | Action\ArticleController@articlePraise | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/article/praise/check/{article_id}` | 文章点赞验证 | Action\ArticleController@articlePraiseCheck | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/article/usermanagement` | 文章列表 | Action\ArticleController@listDataArticle | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/article/usermanagement/{article_id}` | 文章详情 | Action\ArticleController@infoDataArticle | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/article/praises/getcountresult` | 批量获取文章点赞数量和点赞状态 | Action\ArticleController@getArticlePraiseData | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/distributor/bind/checkout` | 检查用户是否绑定店务 | Action\DistributorController@checkDistributor | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/distributor/deliverystaff/checkout` | 检查用户是否是配送员 | Action\DistributorController@checkDeliveryStaff | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/currencyGetDefault` |  | Action\CurrencyController@getDefaultCurrency |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/company/setting` | 获取商城配置信息 | Action\CompanyController@getCompanySetting |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/setting/weburl` | 获取配置外部链接 | Action\CompanyController@getWebUrlSetting |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/company/logistics/list` | 获取公司启用物流列表 | Action\CompanyController@getCompanyLogisticsList |  | frontnoauth:h5app |
| GET | `/api/h5app/traderate/getstatus` | 获取评价状态 | Action\CompanyController@getRateSettingStatus |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/nostores/getstatus` |  | Action\CompanyController@getNostoresStatus |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/company/logistics/enableList` | 获取公司启用的物流列表 | Action\CompanyController@getLogisticsEnableList |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/setting/itemPrice` | 获取商品价格显示设置 | Action\CompanyController@getItemPriceSetting |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/company/privacy_setting_ck` | 获取pc和h5隐私 | Action\CompanyController@getPrivacySetting |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/shops/setting` | 配置门店通用配置信息 | Action\Shops@getWxShopsSetting |  |  |

### `routes/frontapi/datacube.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| POST | `/api/h5app/wxapp/track/viewnum` |  | Action\Track@addViewNum |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/datacube/Deliverystaffdata` |  | Action\DeliveryStaff@getDeliveryStaffData | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/datacube/DeliverystaffdataDetail` |  | Action\DeliveryStaff@getDeliverystaffdataDetail | jwt | dingoguard:h5app,api.auth |

### `routes/frontapi/deposit.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| POST | `/api/h5app/wxapp/deposit/recharge` |  | Action\Recharge@recharge | jwt | dingoguard:h5app,api.auth |
| POST | `/api/h5app/wxapp/deposit/recharge_new` |  | Action\Recharge@rechargeNew | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/deposit/rechargerules` |  | Action\Recharge@getRechargeRuleList | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/deposit/recharge/agreement` |  | Action\Recharge@getRechargeAgreementByCompanyId | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/deposit/list` |  | Action\Deposit@lists | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/deposit/info` |  | Action\Deposit@info | jwt | dingoguard:h5app,api.auth |
| POST | `/api/h5app/wxapp/deposit/to/point` |  | Action\Deposit@depositToPoint | jwt | dingoguard:h5app,api.auth |

### `routes/frontapi/distributor.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| POST | `/api/h5app/wxapp/cash_withdrawal` |  | Action\CashWithdrawal@applyCashWithdrawal | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/cash_withdrawals` |  | Action\CashWithdrawal@getCashWithdrawalList | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/distributor` |  | Action\Distributor@getDistributor | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/distributor/count` |  | Action\Distributor@getDistributorCount | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/distributor/aftersaleaddress` |  | Action\Distributor@getAftersaleAddressByDistributor | jwt | dingoguard:h5app,api.auth |
| POST | `/api/h5app/wxapp/salesman/applyCashWithdrawal` |  | Action\CashWithdrawal@salesmanApplyCashWithdrawal | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/salesman/getCashWithdrawalList` |  | Action\CashWithdrawal@salesmanGetCashWithdrawalList | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/selfdelivery/getDistributorList` |  | Action\SelfDeliveryStaff@getSelfDeliveryStaffDistributor | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/selfdelivery/list` |  | Action\SelfDeliveryStaff@getSelfDeliveryList | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/distributor/whitelistByMember` |  | Action\Distributor@getDistributorListByWhiteMember | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/distributor/is_valid` |  | Action\Distributor@getDistributorIsValid |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/distributor/list` |  | Action\Distributor@getDistributorList |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/distributor/alllist` |  | Action\Distributor@getAllDistributorList |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/distributor/get_all_distributor` |  | Action\Distributor@getAllDistributor |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/distributor/category/list` |  | Action\Distributor@getDistributorCategoryList |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/distributor/self` |  | Action\Distributor@getDistributionSelfDetail |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/distributor/default` |  | Action\Distributor@getDistributionDefaultDetail |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/distributor/deliverytype` |  | Action\Distributor@getDeliveryType |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/distributor/salesperson/qrcode` |  | Action\Distributor@getDistributorSalespersonQrcodeSingle |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/distributor/areainfo` |  | Action\Distributor@getAreaInfo |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/distributor/advertisements` |  | Action\Distributor@getAdvertisements |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/distributor/slider` |  | Action\Distributor@getSlider |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/distributor/image/code` |  | Action\Distributor@getImageVcode |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/distributor/sms/code` |  | Action\Distributor@getSmsCode |  | frontnoauth:h5app |
| POST | `/api/h5app/wxapp/distributor/sms/code` |  | Action\Distributor@checkSmsVcode |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/distributor/getDistributorInfo` | 查找指定门店信息，未指定返回默认门店 | Action\Distributor@getDistributorInfo |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/distributor/merchant/isvaild` | 查询店铺关联商家是否可用 | Action\Distributor@getDistributorMerchantIsvaild |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/distributor/pickuplocation` | 查询附近的门店自提点列表 | Action\Distributor@getNearPickupLocation |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/distributor/aftersaleslocation` | 查询附近的门店自提点列表 | Action\Distributor@getNearAftersalesLocation |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/distributor/checkUserInWhite` | 检查该用户是否在当前白名单内 | Action\Distributor@checkUserValidInDistributor |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/distributor/config/inRule` | 获取店铺进店规则 | Action\Distributor@getInRule |  | frontnoauth:h5app |
| POST | `/api/h5app/wxapp/distributor/config/inRule/check` | 验证店铺进店规则 | Action\Distributor@checkInRule |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/distributor/getAreaByAddress` | 根据地址获取地区信息 | Action\Distributor@getAreaByAddress |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/distributor/getAreaByJwd` | 根据地址获取地区信息-逆解析 | Action\Distributor@getAreaByJwd |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/salesperson` |  | Action\SalespersonController@getSalespersonInfo | jwt | dingoguard:h5app,api.auth |
| POST | `/api/h5app/wxapp/salesperson/complaints` |  | Action\SalespersonController@sendSalespersonComplaints | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/salesperson/complaintsList` |  | Action\SalespersonController@getSalespersonComplaintsList | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/salesperson/complaintsDetail/{id}` |  | Action\SalespersonController@getSalespersonComplaintsDetail | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/usersalespersonrel` |  | Action\SalespersonController@userSalespersonRelationship | jwt | dingoguard:h5app,api.auth |
| POST | `/api/h5app/wxapp/usersalespersonrel` |  | Action\SalespersonController@bindUserSalespersonRelationship | jwt | dingoguard:h5app,api.auth |
| POST | `/api/h5app/wxapp/salesperson/task/share` |  | Action\SalespersonTaskController@share | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/salesperson/nologin` |  | Action\SalespersonController@getSalespersonInfoNologin |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/salesperson/signinQrcode` |  | Action\SalespersonController@getSigninQrcode |  | frontnoauth:h5app |
| POST | `/api/h5app/wxapp/salesperson/signinValid` |  | Action\SalespersonController@validSignin |  | frontnoauth:h5app |
| POST | `/api/h5app/wxapp/salesperson/subtask/post` | 提交子任务参数 | Action\SalespersonTaskController@postSubtask |  | frontnoauth:h5app |
| POST | `/api/h5app/wxapp/salesperson/relationshipcontinuity` | 关系延续埋点 | Action\SalespersonTaskController@relationshipContinuity |  | frontnoauth:h5app |

### `routes/frontapi/employeepurchase.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| GET | `/api/h5app/wxapp/enterprises` | 获取企业列表 | Action\Enterprise@getEnterprisesList |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/employee/email/vcode` | 获取邮箱验证码 | Action\Employee@sendEmailVcode |  | frontnoauth:h5app |
| POST | `/api/h5app/wxapp/employee/check` | 员工验证 | Action\Employee@employeeCheck |  | frontnoauth:h5app |
| POST | `/api/h5app/wxapp/employeepurchase/activity/behavior-report` | 内购活动行为流水上报 | Action\Activity@reportActivityBehavior |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/employeepurchase/activity/detail` | 获取活动详情 | Action\Activity@getActivityDetail |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/user/enterprises` | 获取用户所在企业列表 | Action\Enterprise@getUserEnterprisesList | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/user/enterprise/distributor` | 获取用户所在企业的店铺数据 | Action\Enterprise@getUserEnterpriseDistributor | jwt | dingoguard:h5app,api.auth |
| POST | `/api/h5app/wxapp/employee/auth` | 员工身份验证 | Action\Employee@authentication | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/employee/activitydata` | 获取员工活动数据 | Action\Employee@getActivityData | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/employee/invitelist` | 获取员工邀请亲友列表 | Action\Employee@getInviteList | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/employee/invitecode` | 获取员工邀请码 | Action\Employee@getInviteCode | jwt | dingoguard:h5app,api.auth |
| POST | `/api/h5app/wxapp/employee/relative/bind` | 绑定成为亲友 | Action\Employee@bindRelative | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/employeepurchase/is_open` | 是否开启内购 | Action\Activity@isOpen | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/employeepurchase/activities` | 获取可参与的活动列表 | Action\Activity@getActivityList | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/employeepurchase/internal-sale-eligibility` | 内购资格校验 | Action\Activity@getInternalSaleEligibility | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/employeepurchase/store-home-page/{id}` | 内购模版详情ToC | Action\StoreHomePage@getDetail | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/employeepurchase/activity/items/category` | 获取活动商品关联的分类 | Action\Activity@getActivityItemCategory | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/employeepurchase/activity/items` | 获取活动商品列表 | Action\Activity@getActivityItemList | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/employeepurchase/activity/item/{item_id}` | 获取活动商品详情 | Action\Activity@getActivityItemDetail | jwt | dingoguard:h5app,api.auth |
| POST | `/api/h5app/wxapp/employeepurchase/cart` | 内购购物车新增 | Action\Cart@cartDataAdd | jwt | dingoguard:h5app,api.auth |
| PUT | `/api/h5app/wxapp/employeepurchase/cart` | 内购购物车更新 | Action\Cart@updateCartData | jwt | dingoguard:h5app,api.auth |
| PUT | `/api/h5app/wxapp/employeepurchase/cart/checkstatus` | 修改内购购物车选中状态 | Action\Cart@updateCartCheckStatus | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/employeepurchase/cartcount` | 内购购物车商品数量 | Action\Cart@getCartItemCount | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/employeepurchase/cart` | 获取内购购物车 | Action\Cart@getCartDataList | jwt | dingoguard:h5app,api.auth |
| DELETE | `/api/h5app/wxapp/employeepurchase/cart` | 内购购物车删除 | Action\Cart@delCartData | jwt | dingoguard:h5app,api.auth |
| PUT | `/api/h5app/wxapp/employeepurchase/order/receiver` | 修改订单的收货人信息 | Action\Order@updateOrderReceiver | jwt | dingoguard:h5app,api.auth |

### `routes/frontapi/goods.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| GET | `/api/h5app/wxapp/goods/items` |  | Action\Items@getItemsList |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/goods/items/batch` |  | Action\Items@getBatchItems |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/goods/items/filter` |  | Action\Items@getItemsFilter |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/goods/shopitems` |  | Action\Items@getShopItemsList |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/goods/category` |  | Action\Category@getCategoryList |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/goods/itemintro/{item_id}` |  | Action\Items@getItemsIntro |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/goods/category/{cat_id}` |  | Action\Category@getChildrenCategorys |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/goods/categorylevel` |  | Action\Category@getLevelCategoryList |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/goods/shopcategorylevel` |  | Action\Category@getShopShelvesCategoryList |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/goods/memberprice/{item_id}` |  | Action\Items@getMemberPriceList |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/goods/keywords` |  | Action\Items@getKeywords |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/goods/categoryinfo` |  | Action\Category@getCategoryInfo |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/goods/promoter/category` |  | Action\Category@getPromoterCategoryList |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/goods/get_group_items` |  | Action\ItemsGroupController@getGroupItems |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/goods/items/{item_id}` |  | Action\Items@getItemsDetail |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/goods/newitems` |  | Action\Items@getItemsDetailNew |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/goods/items_price_store/{item_id}` |  | Action\Items@getItemsPriceAndStore |  | frontnoauth:h5app |
| POST | `/api/h5app/wxapp/goods/scancodeAddcart` |  | Action\Items@scanCodeSales | jwt | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/goods/items/{item_id}/fav` |  | Action\Items@getItemsFav | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/goods/checkshare/items` |  | Action\Items@checkShare | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/goods/share/items/{item_id}` |  | Action\Items@getShareInfo | jwt | dingoguard:h5app,api.auth |

### `routes/frontapi/hfpay.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| GET | `/api/h5app/wxapp/hfpay/userapply` | 获取经销员入驻信息 | Action\HfpayDealer@getInfo | jwt | dingoguard:h5app,api.auth |
| POST | `/api/h5app/wxapp/hfpay/applysave` | 保存经销员入驻信息 | Action\HfpayDealer@save | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/hfpay/bankinfo` | 获取单条银行卡信息 | Action\HfpayBank@getInfo | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/hfpay/banklist` | 获取多条银行卡信息 | Action\HfpayBank@getList | jwt | dingoguard:h5app,api.auth |
| POST | `/api/h5app/wxapp/hfpay/banksave` | 保存提现银行卡 | Action\HfpayBank@save | jwt | dingoguard:h5app,api.auth |
| POST | `/api/h5app/wxapp/hfpay/bankdel` | 解除并删除绑定银行卡 | Action\HfpayBank@unBindBank | jwt | dingoguard:h5app,api.auth |

### `routes/frontapi/im.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| GET | `/api/h5app/wxapp/im/meiqia` | 获取im配置 | Action\Im@meiqiaInfo |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/im/meiqia/distributor/{distributor_id}` | 获取店铺美洽客服配置 | Action\Im@getDistributorMeiQiaSetting |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/im/echat` | 获取一洽配置 | Action\EChat@getInfo |  | frontnoauth:h5app |

### `routes/frontapi/kujiale.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| POST | `/api/h5app/wxapp/kujiale/desginList` | 设计师方案列表 | Action\KuController@desginWorkList | jwt | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/kujiale/desginTagsList` | 方案标签列表 | Action\KuController@getDesginTagsList | jwt | frontnoauth:h5app |
| POST | `/api/h5app/wxapp/kujiale/desginDetail` | 设计师方案详情 | Action\KuController@desginWorkDetail | jwt | frontnoauth:h5app |
| POST | `/api/h5app/wxapp/kujiale/viewcount` | 设计师方案详情 | Action\KuController@updateDesignViewCount | jwt | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/kujiale/getProductList` | 获取渲染图商品列表 | Action\KuController@getProductListByPicId | jwt | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/kujiale/getDesignerDetail` | 获取渲染图详情 | Action\KuController@getDesignerPicById | jwt | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/kujiale/getLocationList` | 获取城市列表 | Action\KuController@getLocationList | jwt | frontnoauth:h5app |
| POST | `/api/h5app/wxapp/kujiale/like` | 设计师方案详情 | Action\KuController@updateDesignLikeCount | jwt | dingoguard:h5app,api.auth |

### `routes/frontapi/member.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| GET | `/api/h5app/wxapp/member` |  | Action\Members@getMemberInfo | jwt | datapass |
| GET | `/api/h5app/wxapp/memberinfo` |  | Action\Members@getMemberEditInfo | jwt | dingoguard:h5app,api.auth |
| PUT | `/api/h5app/wxapp/member` |  | Action\Members@updateMember | jwt | dingoguard:h5app,api.auth |
| PUT | `/api/h5app/wxapp/memberinfo` |  | Action\Members@updateMemberNotUseValidationConfig | jwt | dingoguard:h5app,api.auth |
| PUT | `/api/h5app/wxapp/member/mobile` |  | Action\Members@updateMemberMobile | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/barcode` |  | Action\Members@getBarcode | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/member/statistical` |  | Action\Members@getMemberStatistical | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/member/addresslist` |  | Action\Members@getAddressList | jwt | dingoguard:h5app,api.auth |
| POST | `/api/h5app/wxapp/member/address` |  | Action\Members@createAddress | jwt | dingoguard:h5app,api.auth |
| PUT | `/api/h5app/wxapp/member/address/{address_id}` |  | Action\Members@updateAddress | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/member/address/{address_id}` |  | Action\Members@getAddress | jwt | dingoguard:h5app,api.auth |
| DELETE | `/api/h5app/wxapp/member/address/{address_id}` |  | Action\Members@deleteAddress | jwt | dingoguard:h5app,api.auth |
| POST | `/api/h5app/wxapp/member/collect/item/{item_id}` |  | Action\Members@addItemsFav | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/member/collect/item` |  | Action\Members@getItemsFavList | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/member/collect/item/num` |  | Action\Members@getItemsFavNum | jwt | dingoguard:h5app,api.auth |
| DELETE | `/api/h5app/wxapp/member/collect/item` |  | Action\Members@deleteItemsFav | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/member/browse/history/list` |  | Action\Members@getBrowseHistory | jwt | dingoguard:h5app,api.auth |
| POST | `/api/h5app/wxapp/member/browse/history/save` |  | Action\Members@saveBrowseHistory | jwt | dingoguard:h5app,api.auth |
| POST | `/api/h5app/wxapp/member/collect/article/{article_id}` |  | Action\Members@addArticleFav | jwt | dingoguard:h5app,api.auth |
| DELETE | `/api/h5app/wxapp/member/collect/article` |  | Action\Members@deleteArticleFav | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/member/collect/article` |  | Action\Members@getArticleFavList | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/member/collect/article/num` |  | Action\Members@getArticleFavNum | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/member/collect/article/info` |  | Action\Members@getArticleFavInfo | jwt | dingoguard:h5app,api.auth |
| POST | `/api/h5app/wxapp/member/collect/distribution/{distributor_id}` |  | Action\Members@addDistributionFav | jwt | dingoguard:h5app,api.auth |
| DELETE | `/api/h5app/wxapp/member/collect/distribution` |  | Action\Members@deleteDistributionFav | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/member/collect/distribution` |  | Action\Members@getDistributionFavList | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/member/collect/distribution/num` |  | Action\Members@getDistributionFavNum | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/member/collect/distribution/check` |  | Action\Members@checkDistributionFav | jwt | dingoguard:h5app,api.auth |
| POST | `/api/h5app/wxapp/member/subscribe/item/{item_id}` |  | Action\Members@itemsSubscribe | jwt | dingoguard:h5app,api.auth |
| POST | `/api/h5app/wxapp/member/bindSalesperson` |  | Action\Members@bindSalesperson | jwt | dingoguard:h5app,api.auth |
| POST | `/api/h5app/wxapp/member/salesperson/uniquevisito` |  | Action\Members@salespersonUniqueVisito | jwt | dingoguard:h5app,api.auth |
| DELETE | `/api/h5app/wxapp/member` |  | Action\Members@deleteMember | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/member/invoicelist` |  | Action\Members@getInvoiceList | jwt | dingoguard:h5app,api.auth |
| POST | `/api/h5app/wxapp/member/invoice` |  | Action\Members@createInvoice | jwt | dingoguard:h5app,api.auth |
| PUT | `/api/h5app/wxapp/member/invoice/{invoice_id}` |  | Action\Members@updateInvoice | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/member/invoice/{invoice_id}` |  | Action\Members@getInvoice | jwt | dingoguard:h5app,api.auth |
| DELETE | `/api/h5app/wxapp/member/invoice/{invoice_id}` |  | Action\Members@deleteInvoice | jwt | dingoguard:h5app,api.auth |
| POST | `/api/h5app/wxapp/member` |  | Action\Members@creatMember |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/member/setting` |  | Action\Members@getRegSetting |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/member/agreement` |  | Action\Members@getRegAgreementSetting |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/member/sms/code` |  | Action\Members@getSmsCode |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/member/image/code` |  | Action\Members@getImageVcode |  | frontnoauth:h5app |
| POST | `/api/h5app/wxapp/member/reset/password` |  | Action\Members@resetMemberPassword |  | frontnoauth:h5app |
| POST | `/api/h5app/wxapp/member/email/code` |  | Action\Members@sendMemberEmailCode |  | frontnoauth:h5app |
| POST | `/api/h5app/wxapp/member/email/activation/resend` |  | Action\Members@resendActivationEmail |  | frontnoauth:h5app |
| POST | `/api/h5app/wxapp/member/email/register` |  | Action\Members@registerMemberByEmail |  | frontnoauth:h5app |
| POST | `/api/h5app/wxapp/member/email/activate` |  | Action\Members@activateMemberByEmail |  | frontnoauth:h5app |
| POST | `/api/h5app/wxapp/member/email/password/reset-request` |  | Action\Members@requestMemberPasswordResetEmail |  | frontnoauth:h5app |
| POST | `/api/h5app/wxapp/member/email/password/reset` |  | Action\Members@resetMemberPasswordByEmailToken |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/whitelist/status` |  | Action\Members@getWhitelistStatus |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/member/item/is_subscribe/{item_id}` |  | Action\Members@IsSubscribe |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/trustlogin/params` | 获取信任登录参数 | Action\TrustLogin@getTrustLoginParams |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/trustlogin/list` | 获取信任登录参数 | Action\TrustLogin@getTrustLoginList |  | frontnoauth:h5app |
| POST | `/api/h5app/wxapp/member/is_new` |  | Action\Members@isNewMember |  | frontnoauth:h5app |
| POST | `/api/h5app/wxapp/member/bind` |  | Action\Members@bindMember |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/member/addressarea` |  | Action\Members@getAddressArea |  |  |
| GET | `/api/h5app/wxapp/member/decryptPhone` |  | Action\Members@getNoAuthDecryptPhoneNumber |  |  |
| GET | `/api/h5app/wxapp/selfform/statisticalAnalysis` |  | Action\FormTemplateController@statisticalAnalysis | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/selfform/physical/datelist` |  | Action\FormTemplateController@getRecordDateList | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/registrationActivity` |  | Action\RegistrationActivityController@getRegistrationActivity | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/registrationRecordList` |  | Action\RegistrationActivityController@getRegistrationRecordList | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/registrationRecordInfo` |  | Action\RegistrationActivityController@getRegistrationRecordInfo | jwt | dingoguard:h5app,api.auth |
| POST | `/api/h5app/wxapp/registrationSubmit` |  | Action\RegistrationActivityController@registrationSubmit | jwt | dingoguard:h5app,api.auth |
| POST | `/api/h5app/wxapp/joinActivity` |  | Action\RegistrationActivityController@joinActivity | jwt | dingoguard:h5app,api.auth |
| POST | `/api/h5app/wxapp/cancelRecord` |  | Action\RegistrationActivityController@cancelRecord | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/registrationActivityList` |  | Action\RegistrationActivityController@getRegistrationActivityList | jwt | dingoguard:h5app,api.auth |
| POST | `/api/h5app/wxapp/medicationPersonnel` |  | Action\MedicationPersonnel@create | jwt | dingoguard:h5app,api.auth |
| PUT | `/api/h5app/wxapp/medicationPersonnel` |  | Action\MedicationPersonnel@update | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/medicationPersonnel/list` |  | Action\MedicationPersonnel@getList | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/medicationPersonnel/detail` |  | Action\MedicationPersonnel@getDetail | jwt | dingoguard:h5app,api.auth |
| DELETE | `/api/h5app/wxapp/medicationPersonnel` |  | Action\MedicationPersonnel@deleteMedicationPersonnel | jwt | dingoguard:h5app,api.auth |

### `routes/frontapi/merchant.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| GET | `/api/h5app/wxapp/merchant/basesetting` | 获取商户基础设置 | Action\Merchant@getBaseSetting |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/merchant/settlementapply/step` | 获取商户入驻当前步骤 | Action\Merchant@getSettlementApplyStep | jwt | frontmerchantauth:h5app,api.auth |
| GET | `/api/h5app/wxapp/merchant/type/list` | 获取商户类型列表 | Action\Merchant@getVisibleTypeList | jwt | frontmerchantauth:h5app,api.auth |
| POST | `/api/h5app/wxapp/merchant/settlementapply/{step}` | 保存商户入驻信息 | Action\Merchant@saveSettlementApply | jwt | frontmerchantauth:h5app,api.auth |
| GET | `/api/h5app/wxapp/merchant/settlementapply/detail` | 获取商户入驻信息详情 | Action\Merchant@getSettlementApplyDetail | jwt | frontmerchantauth:h5app,api.auth |
| GET | `/api/h5app/wxapp/merchant/settlementapply/auditstatus` | 获取商户入驻信息审核结果 | Action\Merchant@getSettlementApplyAuditstatus | jwt | frontmerchantauth:h5app,api.auth |
| POST | `/api/h5app/wxapp/merchant/password/reset` | 重置商户登录密码 | Action\Merchant@resetMerchantPassword | jwt | frontmerchantauth:h5app,api.auth |
| GET | `/api/h5app/wxapp/merchant/espier/image_upload_token` | 获取商户图片上传token | Action\UploadFile@getPicUploadToken | jwt | frontmerchantauth:h5app,api.auth |

### `routes/frontapi/old.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| POST | `/api/wxapp/login` |  | Action\Wxapp@checkLogin |  |  |
| GET | `/api/wxapp/espier/image_upload_token` |  | Action\UploadFile@getPicUploadToken | wxapp | dingoguard:wechat,api.auth |
| GET | `/api/wxapp/espier/address` |  | Action\AddressController@get | wxapp | dingoguard:wechat,api.auth |
| POST | `/api/wxapp/espier/upload` |  | Action\UploadFile@uploadImage | wxapp | dingoguard:wechat,api.auth |
| GET | `/api/wxapp/user/receiveCard` |  | Action\UserDiscount@receiveCard | wxapp | dingoguard:wechat,api.auth |
| GET | `/api/wxapp/shops/wxshops` |  | Action\Shops@getWxShopsList |  | frontnoauth:wechat |
| GET | `/api/wxapp/shops/wxshops/{wx_shop_id}` |  | Action\Shops@getWxShopsDetail |  | frontnoauth:wechat |
| GET | `/api/wxapp/shops/getNearestWxShops` |  | Action\Shops@getNearestWxShops |  | frontnoauth:wechat |
| GET | `/api/wxapp/shops/wxshops` |  | Action\Shops@getWxShopsList |  | frontnoauth:wechat |
| GET | `/api/wxapp/shops/wxshops/{wx_shop_id}` |  | Action\Shops@getWxShopsDetail |  | frontnoauth:wechat |
| GET | `/api/wxapp/shops/getNearestWxShops` |  | Action\Shops@getNearestWxShops |  | frontnoauth:wechat |
| POST | `/api/wxapp/track/viewnum` |  | Action\Track@addViewNum | wxapp | dingoguard:wechat,api.auth |
| POST | `/api/weapp/deposit/recharge` |  | Action\Recharge@recharge | wxapp | dingoguard:wechat,api.auth |
| GET | `/api/weapp/deposit/rechargerules` |  | Action\Recharge@getRechargeRuleList | wxapp | dingoguard:wechat,api.auth |
| GET | `/api/weapp/deposit/recharge/agreement` |  | Action\Recharge@getRechargeAgreementByCompanyId | wxapp | dingoguard:wechat,api.auth |
| GET | `/api/wxapp/goods/items` |  | Action\Items@getItemsList |  | frontnoauth:wechat |
| GET | `/api/wxapp/goods/items/{item_id}` |  | Action\Items@getItemsDetail |  | frontnoauth:wechat |
| GET | `/api/wxapp/goods/items` |  | Action\Items@getItemsList |  | frontnoauth:wechat |
| GET | `/api/wxapp/goods/items/{item_id}` |  | Action\Items@getItemsDetail |  | frontnoauth:wechat |
| GET | `/api/wxapp/member` |  | Action\Members@getMemberInfo | wxapp | dingoguard:wechat,api.auth |
| PUT | `/api/wxapp/member` |  | Action\Members@updateMember | wxapp | dingoguard:wechat,api.auth |
| GET | `/api/wxapp/member/setting` |  | Action\Members@getRegSetting | wxapp | dingoguard:wechat,api.auth |
| GET | `/api/wxapp/member/agreement` |  | Action\Members@getRegAgreementSetting | wxapp | dingoguard:wechat,api.auth |
| POST | `/api/wxapp/member` |  | Action\Members@creatMember | wxapp | dingoguard:wechat,api.auth |
| GET | `/api/wxapp/barcode` |  | Action\Members@getBarcode | wxapp | dingoguard:wechat,api.auth |
| GET | `/api/wxapp/member/decryptPhoneInfo` |  | Action\Members@getDecryptPhoneNumber | wxapp | dingoguard:wechat,api.auth |
| POST | `/api/wxapp/order` |  | Action\WxappOrder@createOrder | wxapp | dingoguard:wechat,api.auth |
| GET | `/api/wxapp/orders` |  | Action\WxappOrder@getOrderList | wxapp | dingoguard:wechat,api.auth |
| GET | `/api/wxapp/groupOrders` |  | Action\WxappOrder@getGroupOrderList | wxapp | dingoguard:wechat,api.auth |
| GET | `/api/wxapp/groupOrders/{teamId}` |  | Action\WxappOrder@getGroupOrderDetail | wxapp | dingoguard:wechat,api.auth |
| GET | `/api/wxapp/orders/count` |  | Action\WxappOrder@countOrderAndRightsLog | wxapp | dingoguard:wechat,api.auth |
| GET | `/api/wxapp/rights` |  | Action\Rights@getRightsList | wxapp | dingoguard:wechat,api.auth |
| GET | `/api/wxapp/rightsLogs` |  | Action\Rights@getRightsLogList | wxapp | dingoguard:wechat,api.auth |
| GET | `/api/wxapp/rights/{rights_id}` |  | Action\Rights@getRightsDetail | wxapp | dingoguard:wechat,api.auth |
| GET | `/api/wxapp/rightscode/{rights_id}` |  | Action\Rights@getRightsCode | wxapp | dingoguard:wechat,api.auth |
| GET | `/api/wxapp/groupOrders/{teamId}` |  | Action\WxappOrder@getGroupOrderDetail |  | frontnoauth:wechat |
| GET | `/api/wxa/promotion/articles` |  | Action\Wxapp@getPromotionArticles |  | frontnoauth:wechat |
| GET | `/api/wxa/promotion/articles/info` |  | Action\Wxapp@getPromotionArticlesInfo |  | frontnoauth:wechat |
| GET | `/api/wxapp/pageparams/setting` |  | Action\Wxapp@getParamByTempName |  | frontnoauth:wechat |
| GET | `/api/wxapp/share/setting` |  | Action\Wxapp@getShareSetting |  | frontnoauth:wechat |
| GET | `/api/wxa/promotion/articles` |  | Action\Wxapp@getPromotionArticles |  | frontnoauth:wechat |
| GET | `/api/wxa/promotion/articles/info` |  | Action\Wxapp@getPromotionArticlesInfo |  | frontnoauth:wechat |
| GET | `/api/wxapp/pageparams/setting` |  | Action\Wxapp@getParamByTempName |  | frontnoauth:wechat |
| GET | `/api/wxapp/share/setting` |  | Action\Wxapp@getShareSetting |  | frontnoauth:wechat |
| GET | `/api/wxapp/payment/config` |  | Action\WxappPayment@doPayment | wxapp | dingoguard:wechat,api.auth |
| GET | `/api/wxapp/promotion/register` |  | Action\RegisterPromotions@getRegisterPromotionsConfig |  | frontnoauth:wechat |

### `routes/frontapi/orders.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| GET | `/api/h5app/wxapp/order/get_offline_pay_info` | 获取订单线下支付信息 | Action\SupplierOrder@getOfflinePayInfo | jwt | dingoguard:h5app,api.auth |
| POST | `/api/h5app/wxapp/supplier/set_order_pay_status` | 订单已转账 | Action\SupplierOrder@setOrderPayStatus | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/supplier/get_supplier_info` | 查询供应商信息 | Action\Supplier@getSupplierInfo | jwt | dingoguard:h5app,api.auth |
| POST | `/api/h5app/wxapp/order/jspayconfig` |  | Action\WxappOrder@getJsPayConfig | jwt | dingoguard:h5app,api.auth |
| POST | `/api/h5app/wxapp/order` | 创建订单并发起支付 | Action\WxappOrder@createOrder | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/epidemic/info` | 疫情登记信息 | Action\WxappOrder@epidemicRegisterInfo | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/epidemic/mixed/cat` | 疫情登记所需信息 | Action\WxappOrder@epidemicRegisterMixedCats | jwt | dingoguard:h5app,api.auth |
| POST | `/api/h5app/wxapp/epidemic/info/del/{id}` | 删除疫情登记信息 | Action\WxappOrder@delEpidemicRegister | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/order/{order_id}` | 订单详情 | Action\WxappOrder@getOrderDetail | jwt | datapass |
| GET | `/api/h5app/wxapp/order_new/{order_id}` | 订单详情 | Action\WxappOrder@getOrderDetailNew | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/orders` | 获取用户订单列表 | Action\WxappOrder@getOrderList | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/groupOrders` | 拼团订单列表 | Action\WxappOrder@getGroupOrderList | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/orders/count` | 统计订单数量和权益核销数量 | Action\WxappOrder@countOrderAndRightsLog | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/orderscount` |  | Action\WxappOrder@countOrders | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/trackerpull` | 物流跟踪信息 | Action\WxappOrder@trackerpull | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/rights` | 获取权益列表 | Action\Rights@getRightsList | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/rightsLogs` | 获取权益核销记录列表 | Action\Rights@getRightsLogList | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/rights/{rights_id}` | 获取权益详情 | Action\Rights@getRightsDetail | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/rightscode/{rights_id}` | 获取权益核销码 | Action\Rights@getRightsCode | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/ziticode` | 获取自提码 | Action\WxappOrder@getZitiQRCode | jwt | dingoguard:h5app,api.auth |
| POST | `/api/h5app/wxapp/order/cancel` | 订单取消 | Action\WxappOrder@cancelOrder | jwt | dingoguard:h5app,api.auth |
| POST | `/api/h5app/wxapp/order/confirmReceipt` | 确认发货 | Action\WxappOrder@confirmReceipt | jwt | dingoguard:h5app,api.auth |
| POST | `/api/h5app/wxapp/cart` | 购物车增加 | Action\CartController@addCart | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/cart` |  | Action\CartController@getCartList | jwt | dingoguard:h5app,api.auth |
| DELETE | `/api/h5app/wxapp/cartdel` |  | Action\CartController@deleteCartData | jwt | dingoguard:h5app,api.auth |
| DELETE | `/api/h5app/wxapp/cartdelbat` |  | Action\CartController@deleteCartDataBat | jwt | dingoguard:h5app,api.auth |
| PUT | `/api/h5app/wxapp/cartupdate/checkstatus` |  | Action\CartController@updateCartCheckStatus | jwt | dingoguard:h5app,api.auth |
| PUT | `/api/h5app/wxapp/cartupdate/batchnum` |  | Action\CartController@batchUpdateCartNum | jwt | dingoguard:h5app,api.auth |
| PUT | `/api/h5app/wxapp/cartupdate/num` |  | Action\CartController@updateCartNum | jwt | dingoguard:h5app,api.auth |
| PUT | `/api/h5app/wxapp/cartupdate/promotion` |  | Action\CartController@updateCartItemPromotion | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/cartcount` |  | Action\CartController@getCartItemCount | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/orders/invoice` |  | Action\OrderInvoice@getInvoiceList | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/pickupcode/{order_id}` | 获取自提订单提货码 | Action\WxappOrder@getOrderPickupCode | jwt | dingoguard:h5app,api.auth |
| POST | `/api/h5app/wxapp/cart/check/plusitem` |  | Action\CartController@checkPlusItem | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/delivery/lists` | 发货单列表 | Action\Delivery@lists | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/delivery/trackerpull` | 发货单物流详情 | Action\Delivery@deliveryInfo | jwt | dingoguard:h5app,api.auth |
| POST | `/api/h5app/wxapp/order/invoice/apply` | 订单申请发票 | Action\UserInvoice@createInvoice | jwt | dingoguard:h5app,api.auth |
| POST | `/api/h5app/wxapp/order/invoice/update` | 订单申请发票更新 | Action\UserInvoice@updateInvoice | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/order/invoice/list` | 订单申请发票列表 | Action\UserInvoice@getUserInvoiceList | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/order/invoice/info/{id}` | 订单申请发票信息 | Action\UserInvoice@getUserInvoiceDetail | jwt | dingoguard:h5app,api.auth |
| POST | `/api/h5app/wxapp/order/invoice/resend` | 重发发票到邮箱 | Action\UserInvoice@resendInvoiceEmail | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/order/invoice/setting` | 获取开票配置 | Action\UserInvoice@getInvoiceSetting | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/groupOrders/{teamId}` | 拼团订单详情 | Action\WxappOrder@getGroupOrderDetail |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/cart/list` |  | Action\CartController@getDistributorCartList |  | frontnoauth:h5app |
| POST | `/api/h5app/wxapp/getFreightFee` | 获取订单优惠以及运费信息 | Action\WxappOrder@getOrderFreightFeeInfo |  | frontnoauth:h5app |
| POST | `/api/h5app/wxapp/order_new` | 创建订单 | Action\WxappOrder@createNewOrder |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/order/invoice/protocol` | 获取发票协议 | Action\UserInvoice@getInvoiceProtocol |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/espier/subdistrict` | 获取街道社区列表 | Action\SubdistrictController@get |  | frontnoauth:h5app |
| POST | `/api/h5app/wxapp/prescription/diagnosis` | 新增问诊单 | Action\PrescriptionController@createDiagnosis |  | dingoguard:h5app,api.auth |

### `routes/frontapi/pageSetting.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| GET | `/api/h5app/wxa/promotion/articles` |  | Action\Wxapp@getPromotionArticles |  | frontnoauth:h5app |
| GET | `/api/h5app/wxa/promotion/articles/info` |  | Action\Wxapp@getPromotionArticlesInfo |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/pageparams/setting` |  | Action\Wxapp@getParamByTempName |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/share/setting` |  | Action\Wxapp@getShareSetting |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/newtemplate` |  | Action\Wxapp@getWxaNewTmpl |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/membercenter/setting` |  | Action\Wxapp@getMemberCenterParamByTempName |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/common/setting` |  | Action\Wxapp@getCommonSetting |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/cartremind/setting` |  | Action\Wxapp@getCartremindSetting |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/getbyshareid` |  | Action\Wxapp@getByShareId |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/pagestemplate/baseinfo` |  | Action\Wxapp@getPagestemplateBaseinfo |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/pagestemplate/membercenter` |  | Action\Wxapp@getPagestemplateMembercenter |  | frontnoauth:h5app |

### `routes/frontapi/pagestemplate.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| GET | `/api/h5app/wxapp/pagestemplate/detail` | 模板详情 | Action\PagesTemplate@detail |  | apicache |
| GET | `/api/h5app/wxapp/pagestemplate/widget/items` | 获取模板组件商品 | Action\PagesTemplate@getWidgetItems |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/pagestemplate/shopDetail` | 店铺模板详情 | Action\PagesTemplate@shopDetail |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/pagestemplate/setInfo` | 模板设置信息 | Action\PagesTemplate@setInfo |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/pagestemplate/gettdk` | 获取tdk配置信息 | Action\PagesTemplate@getTdk |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/openscreenad` | 开屏广告 | Action\OpenScreenAd@getInfo |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/pctemplate/getTemplateContent` | 获取pc模板装修内容 | Action\PcTemplate@getTemplateContent |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/pctemplate/loginPage/setting` | 获取pc登录页设置 | Action\PcTemplate@getLoginPageSetting |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/memberCenterShare/getInfo` | 获取会员中心分享信息 | Action\MemberCenterShare@getInfo |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/sidebar/info` | 获取侧边栏设置 | Action\PagesSideBar@getPagesSideBar |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/ad/list` | 获取广告列表 | Action\PagesAdPlace@getAdList |  | frontnoauth:h5app |
| GET | `/api/h5app/web/menus/{key}` | Web导航菜单树 | Action\WebMenuFrontAction@show |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/web/menus/id/{id}` | 根据ID获取Web导航菜单树 | Action\WebMenuFrontAction@showById |  | frontnoauth:h5app |

### `routes/frontapi/payment.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| GET | `/api/h5app/wxapp/payment/config` | 获取支付参数 | Action\WxappPayment@doPayment | jwt | dingoguard:h5app,api.auth |
| POST | `/api/h5app/wxapp/payment_deposit` |  | Action\WxappPayment@depositPayment | jwt | dingoguard:h5app,api.auth |
| POST | `/api/h5app/wxapp/payment` | 获取支付需要的参数， 积分以及预存款直接扣除 | Action\WxappPayment@payment | jwt | frontnoauth:h5app |
| POST | `/api/h5app/wxapp/payment/query` | 支付结果查询 | Action\WxappPayment@query | jwt | frontnoauth:h5app |

### `routes/frontapi/point.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| GET | `/api/h5app/wxapp/point/member` | 获取会员积分记录列表 | Action\PointMember@lists | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/point/member/info` | 获取会员积分总数 | Action\PointMember@info | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/point/rule` | 获取积分规则 | Action\PointMemberRule@info |  | frontnoauth:h5app |

### `routes/frontapi/pointsmallgoods.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| GET | `/api/h5app/wxapp/pointsmall/goods/items` | 商品列表 | Action\Items@getItemsList |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/pointsmall/lovely/goods/items` | 猜你喜欢商品列表 | Action\Items@getLovelyItemsList |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/pointsmall/goods/items/{item_id}` | 商品详情 | Action\Items@getItemsDetail |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/pointsmall/setting` | 获取模板设置 | Action\Setting@getSetting |  | frontnoauth:h5app |

### `routes/frontapi/promotions.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| POST | `/api/h5app/wxapp/promotion/formid` | 收录小程序formid | Action\WxaTemplateMsg@setFormId | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/promotion/bargains` | 获取砍价活动列表 | Action\BargainPromotions@getBargainList | jwt | dingoguard:h5app,api.auth |
| POST | `/api/h5app/wxapp/promotion/userbargain` | 参与砍价活动 | Action\UserBargains@createUserBargain | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/promotion/bargainfriendwxappcode` | 获取砍价分享小程序码 | Action\UserBargains@getBargainFriendWxaCode | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/promotion/getMemberCard` | 会员领取付费会员卡 | Action\RegisterPromotions@getMembercardPromotions | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/promotion/seckillactivity/geticket` | 用户获取秒杀资格 | Action\SeckillActivity@getSeckillItemTicket | jwt | dingoguard:h5app,api.auth |
| DELETE | `/api/h5app/wxapp/promotion/seckillactivity/cancelTicket` | 取消会员秒杀资格 | Action\SeckillActivity@cancelSeckillTicket | jwt | dingoguard:h5app,api.auth |
| POST | `/api/h5app/wxapp/promotion/checkin/create` |  | Action\CheckInController@createCheckIn | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/promotion/checkin/getlist` |  | Action\CheckInController@getCheckInList | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/promotion/turntableconfig` |  | Action\Turntable@getTurntableConfig | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/promotion/getLuckyDrawData` |  | Action\Turntable@getLuckyDrawActInfo | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/promotion/getLuckyDrawLog` |  | Action\Turntable@getTurnLog | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/promotion/turntable` |  | Action\Turntable@joinTurntable | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/promotion/loginaddtimes` | 用户登陆赠送抽奖次数 | Action\Turntable@loginAddSurplusTimes | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/promotion/pluspricebuy/getItemList` | 获取加价购活动商品列表 | Action\MarketingActivity@getPlusPriceBuyItem | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/promotion/userbargain` | 获取用户参加砍价活动详情 | Action\UserBargains@getUserBargain | jwt | frontnoauth:h5app |
| POST | `/api/h5app/wxapp/promotion/bargainlog` |  | Action\UserBargains@createBargainLog | jwt | frontnoauth:h5app |
| POST | `/api/h5app/wxapp/promoter` | 会员成为推广员 | Action\PromoterController@changePromoter | jwt | dingoguard:h5app,api.auth |
| PUT | `/api/h5app/wxapp/promoter` |  | Action\PromoterController@updatePromoterInfo | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/promoter/index` | 推广员首页数据 | Action\PromoterController@indexCount | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/promoter/children` |  | Action\PromoterController@getPromoterchildrenList | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/childrenpromoter/list` | 获取推广员的下级推广员 | Action\PromoterController@getChildrenpromoterList | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/promoter/qrcode` | 获取推广员小程序码 | Action\PromoterController@getPromoterQrcode | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/promoter/brokerages` |  | Action\BrokerageController@getBrokerageList | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/promoter/second/brokerages` | 获取B级推广员业绩列表 | Action\BrokerageController@getSecondBrokerageList | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/promoter/brokerage/count` |  | Action\BrokerageController@brokerageCount | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/promoter/brokerage/point_count` |  | Action\BrokerageController@brokeragePointCount | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/promoter/taskBrokerage/logs` |  | Action\BrokerageController@getTaskBrokerageList | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/promoter/taskBrokerage/count` |  | Action\BrokerageController@getTaskBrokerageCountList | jwt | dingoguard:h5app,api.auth |
| POST | `/api/h5app/wxapp/promoter/cash_withdrawal` | 推广员佣金提现申请 | Action\BrokerageController@applyCashWithdrawal | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/promoter/cash_withdrawal` | 推广员佣金提现申请列表 | Action\BrokerageController@getCashWithdrawalList | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/brokerage/qrcode` | 获取推广二维码 | Action\BrokerageController@getBrokerageQrcode | jwt | dingoguard:h5app,api.auth |
| POST | `/api/h5app/wxapp/promoter/relgoods` | 关联推广员关联的商品 | Action\PromoterController@relPromoterGoods | jwt | dingoguard:h5app,api.auth |
| DELETE | `/api/h5app/wxapp/promoter/relgoods` | 删除推广员关联的商品 | Action\PromoterController@deleteRelPromoterGoods | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/promoter/getSalesmanCount` | 业务员统计 | Action\PromoterController@getSalesmanCount | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/promoter/getSalesmanStatic` | 业务员业绩 | Action\PromoterController@getSalesmanStatic | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/promoter/getSalesmanStoreitems` | 业务员推广店铺商品 | Action\PromoterController@getSalesmanStoreitems | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/promoter/info` | 获取推广员基本信息 | Action\PromoterController@getPromoterInfo |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/promoter/relgoods` | 获取推广员关联的商品 | Action\PromoterController@getPromoterGoods |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/promoter/banner` | 获取店招默认封面图 | Action\PromoterController@getPromoterBanner |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/promoter/custompage` | 获取设置虚拟店首页模板 | Action\PromoterController@getPromoterCustompage |  | frontnoauth:h5app |
| POST | `/api/h5app/wxapp/promoter/qrcode/log` | 记录推广员小程序码参数 | Action\PromoterController@logPromoterQrcode |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/promoter/qrcode.png` | 获取推广员小程序码 | Action\PromoterController@getPromoterQrcodePng |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/promoter/new_qrcode.png` | 获取推广邀请码 | Action\PromoterController@getPromoterNewQrcodePng |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/promotion/register` | 获取注册引导营销配置 | Action\RegisterPromotions@getRegisterPromotionsConfig |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/promotions/groups` | 获取拼团列表 | Action\PromotionGroupsActivity@getPromotionGroupsActivityList |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/promotion/seckillactivity/getlist` | 获取秒杀列表 | Action\SeckillActivity@getSeckillList |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/promotion/seckillactivity/getinfo` | 获取秒杀详情及秒杀商品列表 | Action\SeckillActivity@getSeckillInfo |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/promotions/recommendlike` | 获取猜你喜欢列表 | Action\RecommendLikeController@getRecommendLikeLists |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/promotion/getskumarketing` | 获取指定商品的活动信息 | Action\MarketingActivity@getValidMarketingActivityByItemId |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/promotions/package` | 组合商品列表 | Action\PackagePromotions@lists |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/promotions/package/{packageId}` | 组合商品详情 | Action\PackagePromotions@info |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/promotion/fullpromotion/getitemlist` |  | Action\MarketingActivity@getMarketingActivityItemsList |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/promotion/live/list` | 获取直播视频列表 | Action\LiveBroadcast@getLiveList |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/promotion/replay/list` | 获取回放视频列表 | Action\LiveBroadcast@getReplayList |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/alitemplatemessage` | 获取小程序订阅消息模板列表 | Action\AliMiniApp@getTemplateMessage |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/promotiontest/turntableconfig` |  | Action\Turntable@getLuckyDrawActInfo |  | frontnoauth:h5app |
| POST | `/api/h5app/wxapp/sign` | 用户签到 | Action\UserSign@signIn | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/sign/weekly/list` | 用户签到列表 | Action\UserSign@signIn | jwt | dingoguard:h5app,api.auth |

### `routes/frontapi/qrcode.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| GET | `/api/h5app/wxapp/pcqrcode` |  | Controllers\Qrcode@getPcQrcode |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/pcloginqrcode` |  | Controllers\Qrcode@getPcLoginQrcode |  | frontnoauth:h5app |
| POST | `/api/h5app/wxapp/urllink` |  | Action\Wxapp@wxaUrlLink |  | frontnoauth:h5app |
| POST | `/api/h5app/wxapp/urlschema` |  | Action\Wxapp@wxaUrlSchema |  | frontnoauth:h5app |
| GET | `/api/h5app/alipaymini/qrcode.png` |  | Controllers\Qrcode@getQrcode |  | frontnoauth:h5app |

### `routes/frontapi/rate.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| POST | `/api/h5app/wxapp/order/rate/create` | 用户评价 | Action\WxappTradeRate@addRate | jwt | dingoguard:h5app,api.auth |
| POST | `/api/h5app/wxapp/order/replyRate` |  | Action\WxappTradeRate@replyRate | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/order/rate/praise/{rate_id}` | 评价点赞 | Action\WxappTradeRate@ratePraise | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/order/rate/praise/check/{rate_id}` | 评价点赞验证 | Action\WxappTradeRate@ratePraiseCheck | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/order/ratePraise/status` |  | Action\WxappTradeRate@ratePraiseStatus | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/order/rate/praise/num/{rate_id}` |  | Action\WxappTradeRate@ratePraiseNum |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/order/rate/list` | 评价列表 | Action\WxappTradeRate@getRateList |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/order/rate/detail/{rate_id}` | 获取评价详情 | Action\WxappTradeRate@getRateDetail |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/order/replyRate/list` |  | Action\WxappTradeRate@getReplyRateList |  | frontnoauth:h5app |

### `routes/frontapi/reservation.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| POST | `/api/wxapp/reservation` | 用户提交预约数据 | Action\Reservation@createReservation | wxapp | dingoguard:wechat,api.auth |
| GET | `/api/wxapp/reservation/dateDay` | 获取可预约的具体日期 | Action\Reservation@getReservationDate | wxapp | dingoguard:wechat,api.auth |
| GET | `/api/wxapp/reservation/recordlist` | 获取预约记录列表 | Action\Reservation@getRecordList | wxapp | dingoguard:wechat,api.auth |
| GET | `/api/wxapp/reservation/timelist` | 获取可预约的时段 | Action\Reservation@getTimelist | wxapp | dingoguard:wechat,api.auth |
| GET | `/api/wxapp/reservation/getCount` | 获取指定项目的预约量 | Action\Reservation@getRecordCount | wxapp | dingoguard:wechat,api.auth |
| GET | `/api/wxapp/can/reservation/rights` | 获取可用预约项目 | Action\Reservation@getCanReservationRights | wxapp | dingoguard:wechat,api.auth |

### `routes/frontapi/salesperson.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| GET | `/api/h5app/wxapp/salesperson/salemanShopList` | 业务员店铺列表 | Action\ShopSalespersonController@SalemanShopList | jwt | dingoguard:h5app,api.auth |
| POST | `/api/h5app/wxapp/salesperson/bindusersalesperson` | 业务员关系绑定24小时有效 | Action\ShopSalespersonController@bindusersalesperson | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/salespersonadmin/storemanagerinfo` | 查询管理信息 | Action\ShopSalespersonController@storemanagerinfo | jwt | dingoguard:h5app,api.auth |
| POST | `/api/h5app/wxapp/salespersonadmin/addsalesperson` | 增加业务员 | Action\ShopSalespersonController@addsalesperson | jwt | dingoguard:h5app,api.auth |
| POST | `/api/h5app/wxapp/salespersonadmin/updatesalesperson` | 更新业务员 | Action\ShopSalespersonController@updatesalesperson | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/salespersonadmin/salespersonlist` | 查询业务员列表 | Action\ShopSalespersonController@salespersonlist | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/salespersonadmin/salespersoninfo` | 查询业务员列表 | Action\ShopSalespersonController@salespersoninfo | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/salespersonadmin/brokagestaticlist` | 查询业绩统计 | Action\ShopSalespersonController@brokagestaticlist | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/salesperson/distributorlist` | 获取导购店铺列表 | Action\ShopSalespersonController@getDistributorDataList | qywxapp | api.auth |
| GET | `/api/h5app/wxapp/salesperson/distributor/is_valid` | 校验导购员的店铺信息 | Action\ShopSalespersonController@checkDistributorIsValid | qywxapp | api.auth |
| GET | `/api/h5app/wxapp/salesperson/cartdataadd` | 导购员购物车新增 | Action\SalespersonCartController@cartdataAdd | qywxapp | api.auth |
| POST | `/api/h5app/wxapp/salesperson/scancodeAddcart` | 扫条形码加入购物车 | Action\SalespersonCartController@scanCodeSales | qywxapp | api.auth |
| GET | `/api/h5app/wxapp/salesperson/cartdatalist` | 获取导购员购物车 | Action\SalespersonCartController@getCartdataList | qywxapp | api.auth |
| PUT | `/api/h5app/wxapp/salesperson/cartupdate/checkstatus` | 修改购物车选中状态 | Action\SalespersonCartController@updateCartCheckStatus | qywxapp | api.auth |
| GET | `/api/h5app/wxapp/salesperson/cartcount` | 获取购物车数量 | Action\SalespersonCartController@getCartItemCount | qywxapp | api.auth |
| GET | `/api/h5app/wxapp/salesperson/salesPromotion` | 获取导购员促销单 | Action\SalespersonCartController@createSalesPromotion | qywxapp | api.auth |
| POST | `/api/h5app/wxapp/salesperson/bainfo` | 更新导购敏感信息 | Action\SalespersonController@updateBaInfo | qywxapp | api.auth |
| GET | `/api/h5app/wxapp/goods/salesperson/items` | 获取商品列表 | Action\SalespersonItems@getItemList | qywxapp | api.auth |
| GET | `/api/h5app/wxapp/goods/salesperson/itemsinfo` | 获取商品详情 | Action\SalespersonItems@getItemsDetail | qywxapp | api.auth |

### `routes/frontapi/thire_party.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| GET | `/api/h5app/wxapp/third_party/map/key` | 获取地图的key | Action\MapController@get |  | frontnoauth:h5app |

### `routes/frontapi/trade.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| GET | `/api/h5app/wxapp/trade/detail` | 获取支付单详情 | Action\Payment@getTradeDetail | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/trade/setting` | 获取交易配置信息列表 | Action\TradeSetting@getSetting |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/trade/payment/list` | 获取支付配置信息列表 | Action\Payment@getPaymentSettingList | jwt | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/trade/payment/listInfo` | 获取支付配置信息列表 | Action\Payment@getPaymentSettingListInfo | jwt | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/trade/payment/get_setting` | 获取支付配置信息 | Action\Payment@getPaymentSetting | jwt | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/trade/withdraw/list` | 获取提现方式列表 | Action\Payment@getWithDrawList | jwt | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/trade/payment/hfpayversionstatus` | 获取汇付天下版本状态 | Action\Payment@getHfpayVersionStatus | jwt | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/trade/payment/alipay/result` | PC端支付宝支付完成页面返回更新支付状态 | Action\Payment@alipayResult | jwt | frontnoauth:h5app |

### `routes/frontapi/ugc.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| POST | `/api/h5app/wxapp/ugc/follower/create` | 关注/取消关注 | Action\FollowerController@createFollow | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/ugc/follower/list` | 关注/粉丝列表 | Action\FollowerController@getFollowerList | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/ugc/follower/stat` | 获取统计数据 | Action\FollowerController@getFollowerStat | jwt | dingoguard:h5app,api.auth |
| POST | `/api/h5app/wxapp/ugc/comment/create` | 发表评论 | Action\CommentController@createComment | jwt | dingoguard:h5app,api.auth |
| POST | `/api/h5app/wxapp/ugc/comment/like` | 评论点赞/取消点赞 | Action\CommentController@likeComment | jwt | dingoguard:h5app,api.auth |
| POST | `/api/h5app/wxapp/ugc/comment/delete` | 删除评论 | Action\CommentController@deleteComment | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/ugc/comment/list` | 评论列表 | Action\CommentController@getCommentList |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/ugc/post/setting` | 获取设置 | Action\SettingController@getSetting |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/mps/pullfeed` | 拉取Feed文件 | Action\SettingController@pullfeed |  | frontnoauth:h5app |
| POST | `/api/h5app/wxapp/ugc/post/create` | 发布笔记 | Action\PostController@createPost | jwt | dingoguard:h5app,api.auth |
| POST | `/api/h5app/wxapp/ugc/post/delete` |  | Action\PostController@deletePost | jwt | dingoguard:h5app,api.auth |
| POST | `/api/h5app/wxapp/ugc/post/share` | 分享笔记 | Action\PostController@sharePost | jwt | dingoguard:h5app,api.auth |
| POST | `/api/h5app/wxapp/ugc/post/like` | 笔记点赞/取消点赞 | Action\PostController@likePost | jwt | dingoguard:h5app,api.auth |
| POST | `/api/h5app/wxapp/ugc/topic/create` | 创建话题 | Action\TopicController@createTopic | jwt | dingoguard:h5app,api.auth |
| POST | `/api/h5app/wxapp/ugc/tag/create` | 创建图片标签 | Action\TagController@createTag | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/ugc/message/dashboard` | 消息桌面 | Action\MessageController@getMessageDashboard | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/ugc/message/list` | 消息列表 | Action\MessageController@getMessageList | jwt | dingoguard:h5app,api.auth |
| POST | `/api/h5app/wxapp/ugc/message/setTohasRead` | 设置已读 | Action\MessageController@setTohasRead | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/ugc/message/detail` | 消息详情 | Action\MessageController@getMessageDetail | jwt | dingoguard:h5app,api.auth |
| GET | `/api/h5app/wxapp/ugc/post/detail` | 笔记详情 | Action\PostController@getPostDetail |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/ugc/post/list` | 笔记列表 | Action\PostController@getPostList |  | frontnoauth:h5app |
| POST | `/api/h5app/wxapp/ugc/post/favorite` | 笔记收藏 | Action\PostController@favoritePost |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/ugc/topic/detail` | 话题详情 | Action\TopicController@getTopicDetail |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/ugc/topic/list` | 话题列表 | Action\TopicController@getTopicList |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/ugc/tag/detail` | 图片标签详情 | Action\TagController@getTagDetail |  | frontnoauth:h5app |
| GET | `/api/h5app/wxapp/ugc/tag/list` | 图片标签列表 | Action\TagController@getTagList |  | frontnoauth:h5app |

## D. 平台超管 API（61 条）

> 基址 `/api/superadmin/…`、`/api/super/…`，provider `superadmin`


### `routes/super/auth.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| POST | `/api/super/admin/login` |  | Action\SuperAdmin@login |  |  |
| POST | `/api/superadmin/account/add` |  | Action\Accounts@addAccount | jwt | superguard,api.auth |
| PUT | `/api/superadmin/account/updatePassword` |  | Action\Accounts@updatePassword | jwt | superguard,api.auth |
| POST | `/api/superadmin/account/login` |  | closure |  |  |
| GET | `/api/account/token/refresh` |  | closure |  | jwt.refresh |

### `routes/super/companys.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| GET | `/api/superadmin/companys/list` |  | Action\Companys@getCompanysList | jwt | superguard,api.auth |
| PUT | `/api/superadmin/companys` |  | Action\Companys@updateCompany | jwt | superguard,api.auth |
| GET | `/api/superadmin/companys/logs` |  | Action\Companys@getCompanysLogs | jwt | superguard,api.auth |
| POST | `/api/superadmin/distribution/protocol` |  | Action\Companys@UpdateDistributionProtocol | jwt | superguard,api.auth |

### `routes/super/datacube.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| GET | `/api/superadmin/datacube/goodsdata` | 获取商品统计数据 | Action\DataCube@getGoodsData | jwt | superguard,api.auth |
| GET | `/api/superadmin/datacube/companydata` | 获取商城统计数据 | Action\DataCube@getCompanyData | jwt | superguard,api.auth |
| GET | `/api/superadmin/datacube/exportloglist` | 获取文件导出列表 | Action\ExportLog@getExportLogList | jwt | superguard,api.auth |

### `routes/super/espier.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| POST | `/api/superadmin/espier/image_upload_token` | 获取上传图片token | Action\UploadImages@getPicUploadToken | jwt | superguard,api.auth |
| POST | `/api/superadmin/espier/upload_localimage` | 上传图片保存在本地 | Action\UploadImages@uploadeLocalImage | jwt | superguard,api.auth |

### `routes/super/globalconfig.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| GET | `/api/superadmin/globalconfig/getinfo` | 获取全局配置 | Action\Globalconfig@getinfo | jwt | superguard,api.auth |
| POST | `/api/superadmin/globalconfig/saveset` | 设置全局配置 | Action\Globalconfig@saveset | jwt | superguard,api.auth |

### `routes/super/logistics.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| GET | `/api/superadmin/logistics/list` |  | Action\Logistics@getLogisticsList | jwt | superguard,api.auth |
| PUT | `/api/superadmin/logistics` |  | Action\Logistics@updateLogistics | jwt | superguard,api.auth |
| DELETE | `/api/superadmin/logistics/{id}` |  | Action\Logistics@deleteLogistics | jwt | superguard,api.auth |
| PUT | `/api/superadmin/logistics/del` |  | Action\Logistics@batchdeleteLogistics | jwt | superguard,api.auth |
| POST | `/api/superadmin/logistics` |  | Action\Logistics@createLogistics | jwt | superguard,api.auth |
| GET | `/api/superadmin/logistics/init` |  | Action\Logistics@initLogistics | jwt | superguard,api.auth |

### `routes/super/notice.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| GET | `/api/superadmin/notice/list` |  | Action\ShopNotice@getShopNoticeList | jwt | superguard,api.auth |
| POST | `/api/superadmin/notice/add` |  | Action\ShopNotice@addShopNotice | jwt | superguard,api.auth |
| PUT | `/api/superadmin/notice/update` |  | Action\ShopNotice@updateShopNotice | jwt | superguard,api.auth |
| DELETE | `/api/superadmin/notice/delete/{notice_id}` |  | Action\ShopNotice@deleteShopNotice | jwt | superguard,api.auth |
| GET | `/api/superadmin/notice/{notice_id}` |  | Action\ShopNotice@getShopNoticeInfo | jwt | superguard,api.auth |

### `routes/super/operators.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| POST | `/api/superadmin/operator/open` |  | Action\Operators@open | jwt | superguard,api.auth |
| PUT | `/api/superadmin/operator` |  | Action\Operators@updateOperator | jwt | superguard,api.auth |

### `routes/super/permission.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| GET | `/api/super/admin/shopmenu` |  | Action\ShopMenu@getShopMenu | superadmin | api.auth |
| POST | `/api/super/admin/shopmenu` |  | Action\ShopMenu@addShopMenu | superadmin | api.auth |
| PUT | `/api/super/admin/shopmenu` |  | Action\ShopMenu@updateShopMenu | superadmin | api.auth |
| DELETE | `/api/super/admin/shopmenu/{shopmenuId}` |  | Action\ShopMenu@deleteShopMenu | superadmin | api.auth |
| GET | `/api/super/admin/shopmenu/down` |  | Action\ShopMenu@downShopMenu | superadmin | api.auth |
| POST | `/api/super/admin/shopmenu/upload` |  | Action\ShopMenu@uploadMenu | superadmin | api.auth |
| GET | `/api/super/admin/wxapp` |  | Action\wxappTemplate@getTemplateList | superadmin | api.auth |
| POST | `/api/super/admin/wxapp` |  | Action\wxappTemplate@addWxappTemplate | superadmin | api.auth |
| PUT | `/api/super/admin/wxapp` |  | Action\wxappTemplate@updateWxappTemplate | superadmin | api.auth |
| PUT | `/api/super/admin/upgradeTemp` |  | Action\wxappTemplate@upgradeTemp | superadmin | api.auth |
| DELETE | `/api/super/admin/wxapp/{id}` |  | Action\wxappTemplate@deleteWxappTemplate | superadmin | api.auth |
| POST | `/api/super/admin/speedupaudit` |  | Action\wxappTemplate@speedupaudit | superadmin | api.auth |
| POST | `/api/super/admin/domain` |  | Action\wxappTemplate@setDomain | superadmin | api.auth |
| POST | `/api/super/admin/puturl` |  | Action\wxappTemplate@modifyDomain | superadmin | api.auth |
| GET | `/api/superadmin/permission` |  | Action\Accounts@getPermission | jwt | superguard,api.auth |
| GET | `/api/superadmin/shopmenu` |  | Action\ShopMenu@getShopMenu | jwt | superguard,api.auth |
| POST | `/api/superadmin/shopmenu` |  | Action\ShopMenu@addShopMenu | jwt | superguard,api.auth |
| PUT | `/api/superadmin/shopmenu` |  | Action\ShopMenu@updateShopMenu | jwt | superguard,api.auth |
| DELETE | `/api/superadmin/shopmenu/{shopmenuId}` |  | Action\ShopMenu@deleteShopMenu | jwt | superguard,api.auth |
| GET | `/api/superadmin/shopmenu/down` |  | Action\ShopMenu@downShopMenu | jwt | superguard,api.auth |
| POST | `/api/superadmin/shopmenu/upload` |  | Action\ShopMenu@uploadMenu | jwt | superguard,api.auth |
| GET | `/api/superadmin/wxapp` |  | Action\wxappTemplate@getTemplateList | jwt | superguard,api.auth |
| POST | `/api/superadmin/wxapp` |  | Action\wxappTemplate@addWxappTemplate | jwt | superguard,api.auth |
| PUT | `/api/superadmin/wxapp` |  | Action\wxappTemplate@updateWxappTemplate | jwt | superguard,api.auth |
| PUT | `/api/superadmin/upgradeTemp` |  | Action\wxappTemplate@upgradeTemp | jwt | superguard,api.auth |
| DELETE | `/api/superadmin/wxapp/{id}` |  | Action\wxappTemplate@deleteWxappTemplate | jwt | superguard,api.auth |
| POST | `/api/superadmin/speedupaudit` |  | Action\wxappTemplate@speedupaudit | jwt | superguard,api.auth |
| POST | `/api/superadmin/domain` |  | Action\wxappTemplate@setDomain | jwt | superguard,api.auth |
| GET | `/api/superadmin/wxappOplatform/gettemplatedraftlist` |  | Action\OplatformTemplate@gettemplatedraftlist | jwt | superguard,api.auth |
| POST | `/api/superadmin/wxappOplatform/addtotemplate` |  | Action\OplatformTemplate@addtotemplate | jwt | superguard,api.auth |
| GET | `/api/superadmin/wxappOplatform/gettemplatelist` |  | Action\OplatformTemplate@gettemplatelist | jwt | superguard,api.auth |
| POST | `/api/superadmin/wxappOplatform/deletetemplate` |  | Action\OplatformTemplate@deletetemplate | jwt | superguard,api.auth |

## E. ERP 系统对接 API（7 条）

> 基址 `/api/systemlink/…`


### `routes/systemlink/adapay.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| POST | `/api/systemlink/adapay/agent/callback` |  | Action\CallBack@handle |  |  |

### `routes/systemlink/jushuitan.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| POST | `/api/systemlink/jushuitan/{companyId}` |  | Action\Verify@jushuitanApi |  | JushuitanCheck |
| ANY | `/api/systemlink/jushuitan/oauth/callback` |  | Action\Oauth@callback |  |  |

### `routes/systemlink/ome.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| POST | `/api/systemlink/ome` |  | Action\Verify@omeApi |  | ShopexErpCheck |
| POST | `/api/systemlink/ome/{method}` |  | Action\Verify@omeApi |  | ShopexErpCheck |
| POST | `/api/systemlink/ome/createitems` |  | Action\Item@createItems |  | ShopexErpCheck |

### `routes/systemlink/openapi.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| POST | `/api/systemlink/openapi/{method}` |  | Action\Verify@openApi |  | SystemLinkOpenapiCheck |

## F. 第三方回调 / 开放平台 API（38 条）

> 基址 `/api/third/…`、`/api/openapi/…`、`/api/saaserp/…`


### `routes/thirdparty/customs.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| POST | `/api/third/customs/platData` |  | Action\Customs@setPlatData |  |  |
| POST | `/api/third/customs/getOrderData` |  | Action\Customs@getOrderData |  |  |
| POST | `/api/third/customs/updateOrderData` |  | Action\Customs@updateOrderData |  |  |

### `routes/thirdparty/dm.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| POST | `/api/third/dm/messageNotify/{companyId}` |  | Action\Dm@messageNotify |  |  |

### `routes/thirdparty/hfpay.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| POST | `/api/third/hfpay/notify` |  | Action\HfPay@notify |  |  |

### `routes/thirdparty/icbcpay.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| POST | `/api/third/icbcpay/notify` |  | Action\IcbcPay@notify |  |  |

### `routes/thirdparty/kuaizhen.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| POST | `/api/third/kuaizhen/medicineAuditResult` |  | Action\Kuaizhen580@medicineAuditResult |  | Kuaizhen580CallbackCheck |
| POST | `/api/third/kuaizhen/diagnosisFinish` |  | Action\Kuaizhen580@diagnosisFinish |  | Kuaizhen580CallbackCheck |
| POST | `/api/third/kuaizhen/refusePrescribe` |  | Action\Kuaizhen580@refusePrescribe |  | Kuaizhen580CallbackCheck |
| POST | `/api/third/kuaizhen/cancelDiagnosis` |  | Action\Kuaizhen580@cancelDiagnosis |  | Kuaizhen580CallbackCheck |
| POST | `/api/third/kuaizhen/prescriptionMedicationAndAudit` |  | Action\Kuaizhen580@prescriptionMedicationAndAudit |  | Kuaizhen580CallbackCheck |
| POST | `/api/third/kuaizhen/prescriptionMedicationDelete` |  | Action\Kuaizhen580@prescriptionMedicationDelete |  | Kuaizhen580CallbackCheck |

### `routes/thirdparty/openapi.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| ANY | `/api/openapi/exc.operator.resetpwd` |  | Services\run@process |  | OpenapiCommonCheck,handleResponse |
| ANY | `/api/openapi` |  | Services\run@process |  | OpenapiCheck,handleResponse |
| ANY | `/api/openapi/{method}` |  | Services\run@process |  | OpenapiCheck,handleResponse |
| ANY | `/api/openapi/dada/callback/{company_id}` |  | Action\DadaCallback@updateOrderStatus |  | DadaApiCheck |
| ANY | `/api/openapi/shansong/callback/{company_id}` |  | Action\ShansongCallback@updateOrderStatus |  |  |

### `routes/thirdparty/saascert.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| ANY | `/api/third/saascert/cert/validate` |  | Action\SaasCert@certiValidate |  |  |
| ANY | `/api/third/saascert/matrix/callback/{id}` |  | Action\SaasCert@bindrelationCallback |  |  |
| GET | `/api/third/saascert/certificate` |  | Action\SaasCert@getCertificate |  | api.auth |
| GET | `/api/third/saascert/delete/certificate` |  | Action\SaasCert@deleteCertificate |  | api.auth |
| GET | `/api/third/saascert/apply/bindrelation` |  | Action\SaasCert@applyBindrelation |  | api.auth |
| GET | `/api/third/saascert/accept/bindrelation` |  | Action\SaasCert@acceptBindrelation |  | api.auth |
| GET | `/api/third/saascert/isbind` |  | Action\SaasCert@getIsBind |  | api.auth |
| GET | `/api/third/saaserp/log/list` |  | Action\SaasCert@getLogList |  | api.auth |

### `routes/thirdparty/saaserp.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| GET | `/api/saaserp/test/event/{order_id}` |  | Action\Order@testEvent |  | BlockErpTestRoutes |
| GET | `/api/saaserp/test/group/event` |  | Action\Order@testGroupEvent |  | BlockErpTestRoutes |
| GET | `/api/saaserp/test/refund/event` |  | Action\Order@testRefundEvent |  | BlockErpTestRoutes |
| GET | `/api/saaserp/test/aftersales/event` |  | Action\Order@testAftersalesEvent |  | BlockErpTestRoutes |
| GET | `/api/saaserp/test/aftersales/logi/event` |  | Action\Order@testAfterLogiEvent |  | BlockErpTestRoutes |
| GET | `/api/saaserp/test/aftersales/cancel/event` |  | Action\Order@testAftersalesCancelEvent |  | BlockErpTestRoutes |
| POST | `/api/thirdparty/saaserp` |  | Action\Verify@saasErpApi |  | ShopexSaasErpCheck |
| GET | `/api/thirdparty/saaserp/log/list` |  | Action\saasErp@getLogList |  | ShopexSaasErpCheck |

### `routes/thirdparty/shuyun_open_platform_callback.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| POST | `/api/third/shuyun/open-platform/callback/token` |  | Controllers\ShuyunOpenPlatformTokenCallbackController@token |  |  |
| POST | `/api/third/shuyun/open-platform/callback/loyalty-grade` |  | Controllers\ShuyunOpenPlatformLoyaltyGradeCallbackController@callback |  |  |
| POST | `/api/third/shuyun/open-platform/callback/offline-benefit/create` |  | Controllers\ShuyunOfflineBenefitCallbackController@create |  |  |
| POST | `/api/third/shuyun/open-platform/callback/offline-benefit/single-send` |  | Controllers\ShuyunOfflineBenefitCallbackController@singleSend |  |  |
| POST | `/api/third/shuyun/open-platform/callback/offline-benefit/batch-send` |  | Controllers\ShuyunOfflineBenefitCallbackController@batchSend |  |  |

## G. Web 回调路由（Lumen 原生）（8 条）

> 微信授权事件、支付通知等，无 `/api` 前缀


### `routes/web.php`

| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |
|---|---|---|---|---|---|
| GET | `/{verify_name}.txt` |  | Controllers\WorkWechatVerify@domain |  |  |
| POST | `/wechatAuth/events` |  | WecachePush@authorized |  |  |
| POST | `/wechatAuth/callback/{authorizerAppId}` |  | WecachePush@message |  |  |
| POST | `/wechatAuth/wxpay/notify` |  | PaymentNotify@handle |  |  |
| GET | `/wechatAuth/wxapp/qrcode.png` |  | Qrcode@getQrcode |  |  |
| GET | `/payment/paypal/success` |  | PaymentBundle\Http\Controllers\PaypalNotify@handle |  |  |
| GET | `/payment/paypal/cancel` |  | PaymentBundle\Http\Controllers\PaypalNotify@cancel |  |  |
| POST | `/payment/paypal/webhook` |  | PaymentBundle\Http\Controllers\PaypalNotify@webhook |  |  |
