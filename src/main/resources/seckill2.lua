--秒杀券id
local voucherId = ARGV[1]
--用户id
local userId = ARGV[2]
--订单id
local orderId = ARGV[3]


--秒杀券库存缓存key
local stockKey = 'seckill:stock:' .. voucherId
--秒杀券订单key（按券ID隔离，保证一人一券一单）
local orderKey = 'seckill:order:' .. voucherId

--查库存，没有则返回1
if(tonumber(redis.call('get', stockKey)) <= 0) then
    return 1
end

--判断用户是否下过单，若下过单返回2
if(redis.call('sismember', orderKey, userId) == 1) then
    return 2
end

--扣库存
redis.call('incrby', stockKey, -1)
--下单（存userId到券的集合里）
redis.call('sadd', orderKey, userId)
--发送消息到消息队列
redis.call('xadd','streams.order','*','userId',userId,'voucherId',voucherId,'id',orderId)
return 0
