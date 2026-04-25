-- ARGV: userId, orderId, returnTimeMillis
local userId = ARGV[1]
local orderId = ARGV[2]
local returnTime = ARGV[3]

local activeTypeKey = KEYS[1] -- rental:active:{userId}:{deviceName}
local activeSetKey = KEYS[2] -- rental:active:set:{userId}
local orderKey = KEYS[3] -- rental:order:{orderId}

local activeOrder = redis.call('GET', activeTypeKey)
if (activeOrder == false or activeOrder ~= orderId) then
    return 2 -- not renting this order
end

local orderUser = redis.call('HGET', orderKey, 'userId')
if (orderUser == false or orderUser ~= userId) then
    return 5 -- order not found or user mismatch
end

local status = redis.call('HGET', orderKey, 'status')
if (status ~= '1') then
    return 4 -- already returned/cancelled
end

local stationId = redis.call('HGET', orderKey, 'stationId')
local deviceId = redis.call('HGET', orderKey, 'deviceId')
if (stationId == false or deviceId == false) then
    return 6 -- missing order fields
end

local stockKey = 'rental:stock:' .. stationId .. ':' .. deviceId
redis.call('INCR', stockKey)
redis.call('DEL', activeTypeKey)
redis.call('SREM', activeSetKey, orderId)
redis.call('HSET', orderKey, 'status', '2', 'returnTime', returnTime)

return 0
