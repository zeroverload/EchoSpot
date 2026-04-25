-- ARGV: stationId, deviceId, userId, orderId, rentTimeMillis, deviceName
local stationId = ARGV[1]
local deviceId = ARGV[2]
local userId = ARGV[3]
local orderId = ARGV[4]
local rentTime = ARGV[5]
local deviceName = ARGV[6]

local stockKey = 'rental:stock:' .. stationId .. ':' .. deviceId
local activeTypeKey = KEYS[1] -- rental:active:{userId}:{deviceName}
local activeSetKey = KEYS[2] -- rental:active:set:{userId}
local orderKey = KEYS[3] -- rental:order:{orderId}

local stock = tonumber(redis.call('GET', stockKey))
if (stock == nil) then
    return 3 -- stock key missing
end
if (stock <= 0) then
    return 1 -- out of stock
end
if (redis.call('EXISTS', activeTypeKey) == 1) then
    return 2 -- already renting same type
end

redis.call('DECR', stockKey)
redis.call('SET', activeTypeKey, orderId)
redis.call('SADD', activeSetKey, orderId)
redis.call('HSET', orderKey,
        'userId', userId,
        'stationId', stationId,
        'deviceId', deviceId,
        'deviceName', deviceName,
        'status', '1',
        'rentTime', rentTime)

return 0
