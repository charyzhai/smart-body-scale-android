package com.example.dianzicheng.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room 数据库主类，管理 members 和 measurements 两张表。
 *
 * - version = 2：measurements 表新增 5 个派生指标列（BMR / 骨骼肌量 / 皮下脂肪率 /
 *   内脏脂肪等级 / 身体年龄），由 [MIGRATION_1_2] 就地补列，旧记录这些列填 0
 * - [Converters]：提供 [Sex] 枚举与字符串之间的类型转换，使枚举可直接存储在 SQLite 中
 *
 * 实例通过 Room.databaseBuilder() 在 MainActivity 中创建，保证全局单例。
 */
@Database(entities = [MemberEntity::class, MeasurementEntity::class], version = 2, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    /** 提供对 members 和 measurements 表的所有 DAO 操作 */
    abstract fun scaleDao(): ScaleDao

    companion object {
        /**
         * v1 → v2 迁移：就地给 measurements 加 5 列，不重建表、不丢数据。
         *
         * 用 `NOT NULL DEFAULT 0` 而非可空列：旧记录读到 0 即代表"该指标尚未计算"，
         * UI 层据此显示 `--`，用户点一次「重新计算历史体成分」即可回填真实值。
         * 默认值必须与 [MeasurementEntity] 上的 `@ColumnInfo(defaultValue = "0")` 一致，
         * 否则 Room 启动校验会报 schema 不匹配。
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE measurements ADD COLUMN basalMetabolismKcal REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE measurements ADD COLUMN skeletalMuscleKg REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE measurements ADD COLUMN subcutaneousFatPct REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE measurements ADD COLUMN visceralFatLevel REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE measurements ADD COLUMN bodyAge INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
