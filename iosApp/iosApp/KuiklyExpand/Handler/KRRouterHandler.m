#import "KRRouterHandler.h"
#import "KuiklyRenderViewController.h"

static const NSTimeInterval kNavFadeOut = 0.09;
static const NSTimeInterval kNavFadeGap = 0.03;
static const NSTimeInterval kNavFadeIn = 0.21;

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

        KuiklyNavFadeThrough(controller.navigationController.view ?: controller.view, ^{
            [controller.navigationController pushViewController:renderViewController animated:NO];
        });
    } else {

        [controller.navigationController pushViewController:renderViewController animated:YES];
    }
}

- (void)closePage:(UIViewController *)controller {
    BOOL fade = [controller isKindOfClass:[KuiklyRenderViewController class]] &&
        [(KuiklyRenderViewController *)controller usesFadeTransition];
    if (fade) {

        KuiklyNavFadeThrough(controller.navigationController.view ?: controller.view, ^{
            [controller.navigationController popViewControllerAnimated:NO];
        });
    } else {

        [controller.navigationController popViewControllerAnimated:YES];
    }
}

- (BOOL)isModuleSwitch:(nullable NSDictionary *)pageData {
    if (![pageData isKindOfClass:[NSDictionary class]]) {
        return NO;
    }
    id value = pageData[@"transition"];
    return [value isKindOfClass:[NSString class]] && [value isEqualToString:@"fade"];
}

@end
