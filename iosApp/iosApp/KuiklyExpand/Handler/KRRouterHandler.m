#import "KRRouterHandler.h"
#import "KuiklyRenderViewController.h"

@implementation KRRouterHandler

+ (void)load {
    [KRRouterModule registerRouterHandler:[self new]];
}

- (void)openPageWithName:(NSString *)pageName pageData:(NSDictionary *)pageData controller:(UIViewController *)controller {
    KuiklyRenderViewController *renderViewController = [[KuiklyRenderViewController alloc] initWithPageName:pageName pageData:pageData];
    if ([self isModuleSwitch:pageData]) {
        // 同级模块（底部 Tab 之间）不是「下钻」，用 cross-dissolve 代替 push 的横切。
        // 横切会让人以为模块被压进了导航栈，返回时还要朝反方向再滑一次。
        // 时长与 Android 侧 kr_module_fade_in 对齐（210ms）。
        UIView *hostView = controller.navigationController.view ?: controller.view;
        [UIView transitionWithView:hostView
                          duration:0.21
                           options:UIViewAnimationOptionTransitionCrossDissolve | UIViewAnimationOptionAllowAnimatedContent
                        animations:^{
            [controller.navigationController pushViewController:renderViewController animated:NO];
        } completion:nil];
    } else {
        // 层级下钻：系统 UINavigationController push，新页从右滑入、旧页视差左移。
        // 这就是 iOS 导航的标准方向性转场，不要再包自定义动画。
        [controller.navigationController pushViewController:renderViewController animated:YES];
    }
}

- (void)closePage:(UIViewController *)controller {
    BOOL fade = [controller isKindOfClass:[KuiklyRenderViewController class]] &&
        [(KuiklyRenderViewController *)controller usesFadeTransition];
    if (fade) {
        // 同级模块怎么淡入，就怎么淡出，避免「进淡、出切」。
        UIView *hostView = controller.navigationController.view ?: controller.view;
        [UIView transitionWithView:hostView
                          duration:0.21
                           options:UIViewAnimationOptionTransitionCrossDissolve | UIViewAnimationOptionAllowAnimatedContent
                        animations:^{
            [controller.navigationController popViewControllerAnimated:NO];
        } completion:nil];
    } else {
        // 下钻返回：系统 pop，当前页从右滑出，下层页视差回到原位。
        [controller.navigationController popViewControllerAnimated:YES];
    }
}

/// pageData 里带 transition=fade 的是同级模块切换（由 common 侧 Pager.openModule 打标）。
- (BOOL)isModuleSwitch:(nullable NSDictionary *)pageData {
    if (![pageData isKindOfClass:[NSDictionary class]]) {
        return NO;
    }
    id value = pageData[@"transition"];
    return [value isKindOfClass:[NSString class]] && [value isEqualToString:@"fade"];
}

@end
