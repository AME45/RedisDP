-- 比较标识的线程id与锁中是否一致

if(redis.call('get','KEYS[1]') == ARVG[1]) then
    return redis.call('del',KEYS[1])
end
return 0