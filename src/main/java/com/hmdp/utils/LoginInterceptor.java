package com.hmdp.utils;

import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

public class LoginInterceptor implements HandlerInterceptor {
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //1.获取session
        HttpSession session = request.getSession();
        //2.从session中获取user
        Object user = session.getAttribute("user");
        //3.判断用户存在
        if(user == null) {
            //4.不存在返回401状态码
            response.setStatus(401);
            return false;
        }
        //5.存在则保存到threadLocal
        UserHolder.saveUser((UserDTO) user);
        //6.放行
        return true;
    }

    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
       UserHolder.removeUser();
    }
}
