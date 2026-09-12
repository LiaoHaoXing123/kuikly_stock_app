#import "KRRouterHandler.h"
#import "KuiklyRenderViewController.h"

// 同级模块（底部 Tab）之间的三段式淡入淡出：出场 90ms → 间隙 30ms → 入场 210ms。
// 规格与 Android 的 kr_module_fade_out/in.xml、common 侧 AppMotion 逐一对齐
// （TAB_OUT_MS / TAB_GAP_MS / TAB_IN_MS）。
//
// 为什么不用 UIViewAnimationOptionTransitionCrossDissolve：它是两页同时淡，
// 中段会同时看到两份内容，版式相近而文案不同，观感就是「错位」。拆成三段后
// 任一时刻只有一页在变透明度；间隙那 30ms 由 window 底色兜住。
static const NSTimeInterval kNavFadeOut = 0.09;
static const NSTimeInterval kNavFadeGap = 0.03;
static const NSTimeInterval kNavFadeIn = 0.21;

/// 对整屏 hostView 走「淡出 → 换页 → 等 30ms → 淡入」。commit 里做 push/pop（不带系统动画）。
static void KuiklyNavFadeThrough(UIView *hostView, void (^commit)(void)) {
    if (!hostView) {
        commit();
        return;
    }
    [UIView animateWithDuration:kNavFadeOut
                          delay:0
                        options:UIViewAnimationOptionCurveEaseIn
                     animations:^{
        hostView.alpha = 0;
    }
                     completion:^(BOOL finished) {
        commit();
        [UIView animateWithDuration:kNavFadeIn
                              delay:kNavFadeGap
                            options:UIViewAnimationOptionCurveEaseOut
                         animations:^{
            hostView.alpha = 1;
        }
                         completion:nil];
    }];
}

@implementation KRRouterHandler

+ (void)load {
    [KRRouterModule registerRouterHandler:[self new]];
}

- (void)openPageWithName:(NSString *)pageName pageData:(NSDictionary *)pageData controller:(UIViewController *)controller {
    KuiklyRenderViewController *renderViewController = [[KuiklyRenderViewController alloc] initWithPageName:pageName pageData:pageData];
    if ([self isModuleSwitch:pageData]) {
        // 同级模块（底部 Tab 之间）不是「下钻」，走三段式淡入淡出代替 push 的横切。
        // 横切会让人以为模块被压进了导航栈，返回时还要朝反方向再滑一次。
        KuiklyNavFadeThrough(controller.navigationController.view ?: controller.view, ^{
            [controller.navigationController pushViewController:renderViewController animated:NO];
        });
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
        KuiklyNavFadeThrough(controller.navigationController.view ?: controller.view, ^{
            [controller.navigationController popViewControllerAnimated:NO];
        });
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
