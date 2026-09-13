#import "HRBridgeModule.h"

#import "KuiklyRenderViewController.h"
#import <OpenKuiklyIOSRender/NSObject+KR.h>

#define REQ_PARAM_KEY @"reqParam"
#define CMD_KEY @"cmd"
#define FROM_HIPPY_RENDER @"from_hippy_render"

@implementation HRBridgeModule

@synthesize hr_rootView;

- (void)copyToPasteboard:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] hr_stringToDictionary];
    NSString *content = params[@"content"];
    UIPasteboard *pasteboard = [UIPasteboard generalPasteboard];
    pasteboard.string = content;
}

- (void)log:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] hr_stringToDictionary];
    NSString *content = params[@"content"];
    NSLog(@"KuiklyRender:%@", content);
}

- (void)vibrate:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] hr_stringToDictionary];
    NSString *style = params[@"style"];
    if (@available(iOS 10.0, *)) {
        UIImpactFeedbackStyle impactStyle = UIImpactFeedbackStyleLight;
        if ([style isEqualToString:@"medium"]) {
            impactStyle = UIImpactFeedbackStyleMedium;
        } else if ([style isEqualToString:@"heavy"]) {
            impactStyle = UIImpactFeedbackStyleHeavy;
        }

        dispatch_async(dispatch_get_main_queue(), ^{
            UIImpactFeedbackGenerator *generator =
                [[UIImpactFeedbackGenerator alloc] initWithStyle:impactStyle];
            [generator impactOccurred];
        });
    }
}

@end
