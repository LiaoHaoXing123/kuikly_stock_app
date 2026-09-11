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
        // 时长与 Android 侧 KRRouterAdapter 的 kr_module_fade_in 对齐（210ms）。
        UIView *hostView = controller.navigationController.view ?: controller.view;
        [UIView transitionWithView:hostView
                          duration:0.21
                           options:UIViewAnimationOptionTransitionCrossDissolve | UIViewAnimationOptionAllowAnimatedContent
                        animations:^{
            [controller.navigationController pushViewController:renderViewController animated:NO];
        } completion:nil];
    } else {
        [controller.navigationController pushViewController:renderViewController animated:YES];
    }
}

- (void)closePage:(UIViewController *)controller {
    [controller.navigationController popViewControllerAnimated:YES];
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
