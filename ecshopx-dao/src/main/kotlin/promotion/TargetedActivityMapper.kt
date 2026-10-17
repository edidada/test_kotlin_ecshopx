package com.ecshopx.kotlin.dao.promotion

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select

/**
 * `promotions_activity`（会员定向自动营销）的**只读视图**（docs/04 §14.4 末段）。
 *
 * 这张表与满减满折无关：它的 `discount_config` 是「等级 → 券」的发券映射，
 * 形态 `{"coupons":{"<grade_id>":[{"id":<coupon_id>,"count":n}]}, "goods":{…}}`，
 * 只在 CP-A8「按等级发券」里读一次。docs/05 把它写成满减的规则列，是列名漂移，
 * 满减的阶梯在 `promotions_marketing_activity.condition_value`（见 [MarketingActivityMapper]）。
 *
 * 本项目不做这条链路的写面（活动编排、触发条件、定时发券属 P2），所以这里只有读方法，
 * `goods` 节点认得但不解析 —— 发券之外的定向动作一律不执行。
 */
class TargetedActivityDO {
    var activityId: Long = 0
    var companyId: Long = 0
    var activityType: String = ""
    var title: String = ""

    /** JSON 串：{coupons:{grade_id:[{id,count}]}, goods:{…}} */
    var discountConfig: String? = null
    var activityStatus: String = ""
    var beginTime: Long = 0
    var endTime: Long = 0
}

@Mapper
interface TargetedActivityMapper {

    /** 在架且启用的定向活动（CP-A8 未显式给 coupon_id 时按等级反查该发哪张券） */
    @Select(
        "SELECT activity_id, company_id, activity_type, title, discount_config, activity_status, begin_time, end_time " +
            "FROM promotions_activity WHERE company_id = #{companyId} AND activity_status <> 'draft' " +
            "AND begin_time <= #{now} AND (end_time = 0 OR end_time >= #{now}) ORDER BY activity_id DESC LIMIT 20",
    )
    fun listActive(@Param("companyId") companyId: Long, @Param("now") now: Long): List<TargetedActivityDO>
}
