package com.example.dianzicheng.domain

import java.util.Calendar

/**
 * 年龄与出生日期换算工具。
 *
 * 数据库只存 `birthDateEpochMs`（出生日期时间戳），但用户填写、界面展示都是「岁」，
 * 因此需要一个口径唯一的双向换算。此前这段逻辑散在界面和算法里各写一份，
 * 一旦某处少减一次「今年生日是否已过」，就会出现「填 35 岁存成 36 岁」这类偏差。
 *
 * 约定：
 * - 年龄按**周岁**计算（今年生日未过则减 1），与 [com.example.dianzicheng.domain.BodyAlgorithm] 一致；
 * - 由年龄反推生日时，保持「月-日」与当天相同，保证 [ageFromBirthDate] 算回来恰好等于原值。
 */
object AgeUtils {

    /** 未设置生日时使用的默认年龄，与体成分算法中的兜底值保持一致 */
    const val DEFAULT_AGE = 30

    /** 合法年龄区间：算法内部按 1~120 截断，这里保持一致 */
    private val VALID_RANGE = 1..120

    /**
     * 可接受的出生年份下限。
     *
     * 注意不能简单地用 `birthDateEpochMs <= 0` 判断「未设置」：
     * Unix 时间戳以 1970-01-01 为零点，**1970 年前出生的人时间戳是负数**。
     * 早期实现把负数一律当作未填写，导致 60 岁以上成员被默认成 30 岁参与计算，
     * 体脂率、基础代谢、内脏脂肪、身体年龄全部偏低。因此改为按年份区间校验，
     * 只把 0L（约定的未设置哨兵值）和明显不合理的年份判为无效。
     */
    private const val MIN_BIRTH_YEAR = 1900

    /**
     * 由出生日期计算周岁。
     *
     * @param birthDateEpochMs 出生日期（Unix 毫秒时间戳，可为负数，代表 1970 年前）
     * @param nowEpochMs 参照时间，默认当前时刻；显式传入便于单测固定时间
     * @return 周岁；出生日期未设置（0L）、年份超出 1900~今年、或晚于参照时间时返回 null
     */
    fun ageFromBirthDate(
        birthDateEpochMs: Long,
        nowEpochMs: Long = System.currentTimeMillis()
    ): Int? {
        // 0L 是本应用约定的「未设置」哨兵值；其余值按真实日期解析（含负数）
        if (birthDateEpochMs == 0L) return null
        val today = Calendar.getInstance().apply { timeInMillis = nowEpochMs }
        val birth = Calendar.getInstance().apply { timeInMillis = birthDateEpochMs }
        if (birth.after(today)) return null
        val birthYear = birth.get(Calendar.YEAR)
        if (birthYear < MIN_BIRTH_YEAR || birthYear > today.get(Calendar.YEAR)) return null

        var age = today.get(Calendar.YEAR) - birth.get(Calendar.YEAR)
        val todayMonth = today.get(Calendar.MONTH)
        val todayDay = today.get(Calendar.DAY_OF_MONTH)
        val birthMonth = birth.get(Calendar.MONTH)
        val birthDay = birth.get(Calendar.DAY_OF_MONTH)
        // 今年生日还没到：周岁尚未增加
        if (todayMonth < birthMonth || (todayMonth == birthMonth && todayDay < birthDay)) {
            age--
        }
        return age.coerceIn(VALID_RANGE.first, VALID_RANGE.last)
    }

    /**
     * 由周岁反推出生日期。
     *
     * 取「N 年前的今天」作为生日，月日与当天相同，这样再算回去恰好是 N 岁，
     * 不会出现「填 35 显示 34」的错位。
     *
     * @param age 周岁，超出 1~120 会被截断
     * @param nowEpochMs 参照时间，默认当前时刻
     * @return 出生日期（Unix 毫秒时间戳）
     */
    fun birthDateFromAge(age: Int, nowEpochMs: Long = System.currentTimeMillis()): Long {
        val safeAge = age.coerceIn(VALID_RANGE.first, VALID_RANGE.last)
        return Calendar.getInstance().apply {
            timeInMillis = nowEpochMs
            add(Calendar.YEAR, -safeAge)
        }.timeInMillis
    }

    /**
     * 展示用年龄：出生日期非法时回落到 [DEFAULT_AGE]，保证界面不出现「0 岁」。
     */
    fun displayAge(birthDateEpochMs: Long, nowEpochMs: Long = System.currentTimeMillis()): Int =
        ageFromBirthDate(birthDateEpochMs, nowEpochMs) ?: DEFAULT_AGE
}
