#import <UIKit/UIKit.h>
NS_ASSUME_NONNULL_BEGIN

@interface KuiklyRenderViewController : UIViewController

- (instancetype)initWithPageName:(NSString *)pageName pageData:(NSDictionary *)pageData;

- (BOOL)usesFadeTransition;

@end

NS_ASSUME_NONNULL_END
