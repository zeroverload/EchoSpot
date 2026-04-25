-- Shared device rental tables (MySQL)
-- 说明：本项目沿用原有 `tb_shop` 作为“站点 station”，因此 `tb_device.station_id` 指向 `tb_shop.id`

DROP TABLE IF EXISTS `tb_station`;
CREATE TABLE `tb_station`  (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,
  `name` varchar(64) NOT NULL,
  `address` varchar(255) NULL DEFAULT NULL,
  `latitude` double NULL DEFAULT NULL,
  `longitude` double NULL DEFAULT NULL,
  `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

DROP TABLE IF EXISTS `tb_device`;
CREATE TABLE `tb_device`  (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,
  `station_id` bigint(20) UNSIGNED NOT NULL,
  `name` varchar(64) NOT NULL,
  `model` varchar(64) NULL DEFAULT NULL,
  `stock` int(11) NOT NULL DEFAULT 0,
  `status` tinyint(1) NOT NULL DEFAULT 1 COMMENT '0=下线 1=在线',
  `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_station` (`station_id`) USING BTREE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

DROP TABLE IF EXISTS `tb_rental_order`;
CREATE TABLE `tb_rental_order`  (
  `id` bigint(20) UNSIGNED NOT NULL COMMENT '订单ID（RedisIdWorker 生成）',
  `user_id` bigint(20) UNSIGNED NOT NULL,
  `station_id` bigint(20) UNSIGNED NOT NULL,
  `device_id` bigint(20) UNSIGNED NOT NULL,
  `status` tinyint(1) NOT NULL DEFAULT 1 COMMENT '1=租借中 2=已归还 3=已取消',
  `rent_time` datetime NULL DEFAULT NULL,
  `return_time` datetime NULL DEFAULT NULL,
  `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_user_status` (`user_id`, `status`) USING BTREE,
  KEY `idx_station_device` (`station_id`, `device_id`) USING BTREE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 示例数据：把 shopId=1 视作一个站点，在其下挂 2 个设备
-- 你可以先确认 `tb_shop` 里是否存在 id=1 的记录，再按需修改 station_id。
INSERT INTO `tb_device` (`station_id`, `name`, `model`, `stock`, `status`)
VALUES
  (1, '共享充电宝', 'PB-10000', 50, 1),
  (1, '共享雨伞', 'UM-01', 30, 1);

