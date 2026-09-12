#import <UIKit/UIKit.h>
NS_ASSUME_NONNULL_BEGIN

@interface KuiklyRenderViewController : UIViewController

/*
 * @brief 创建实例对应的初始化方法.
 * @param pageName 页面名 （对应的值为kotlin侧页面注解 @Page("xxxx")中的xxx名）
 * @param params 页面对应的参数（kotlin侧可通过pageData.params获取）
 * @return 返回KuiklyRenderViewController实例
 */
- (instancetype)initWithPageName:(NSString *)pageName pageData:(NSDictionary *)pageData;

/// 同级模块（底部 Tab）带 transition=fade。返回时要走同一套三段式淡入淡出，
/// 不能再走 push 的反向横切。
- (BOOL)usesFadeTransition;

@end

NS_ASSUME_NONNULL_END
