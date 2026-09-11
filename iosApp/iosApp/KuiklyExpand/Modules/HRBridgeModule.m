#import "HRBridgeModule.h"

#import "KuiklyRenderViewController.h"
#import <OpenKuiklyIOSRender/NSObject+KR.h>

#define REQ_PARAM_KEY @"reqParam"
#define CMD_KEY @"cmd"
#define FROM_HIPPY_RENDER @"from_hippy_render"
// 扩展桥接接口
/*
 * @brief Native暴露接口到kotlin侧，提供kotlin侧调用native能力
 */

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

// 触觉反馈，与 Android KRBridgeModule.vibrate() 对齐：light / medium / heavy 三档。
// 用 UIImpactFeedbackGenerator 而不是 AudioServicesPlaySystemSound(kSystemSoundID_Vibrate)：
// 前者是 Taptic Engine 的"轻点"手感，系统会按机型整形，且受系统"触感"开关控制。
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
        // Taptic Engine 需在主线程触发
        dispatch_async(dispatch_get_main_queue(), ^{
            UIImpactFeedbackGenerator *generator =
                [[UIImpactFeedbackGenerator alloc] initWithStyle:impactStyle];
            [generator impactOccurred];
        });
    }
}

@end