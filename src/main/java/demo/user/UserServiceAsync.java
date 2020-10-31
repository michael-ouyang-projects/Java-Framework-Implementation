package demo.user;

import java.util.List;

import tw.framework.michaelcore.async.annotation.Async;
import tw.framework.michaelcore.data.annotation.Transactional;
import tw.framework.michaelcore.ioc.annotation.Autowired;
import tw.framework.michaelcore.ioc.annotation.Service;

@Async
@Service
public class UserServiceAsync implements IUserService {

	@Autowired
    private UserService userService;

    public List<User> queryAll() {
        return userService.queryAll();
    }

    @Transactional
    public void addUser(User user) {
    	try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    	userService.addUser(user);
    }
	
}
