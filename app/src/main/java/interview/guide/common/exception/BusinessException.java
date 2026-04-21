package interview.guide.common.exception;

import lombok.Getter;

/**
 * 业务异常
 */
@Getter
public class BusinessException extends RuntimeException {

    private final Integer code;
    private final String message;
    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.code = errorCode.getCode();
        this.message = errorCode.getMessage();
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.code = errorCode.getCode();
        this.message = message;
    }

    public BusinessException(Integer code, String message) {
        super(message);
        this.errorCode = null;
        this.code = code;
        this.message = message;
    }

    public BusinessException(String message) {
        super(message);
        this.errorCode = ErrorCode.INTERNAL_ERROR;
        this.code = ErrorCode.INTERNAL_ERROR.getCode();
        this.message = message;
    }

    public BusinessException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = ErrorCode.INTERNAL_ERROR;
        this.code = ErrorCode.INTERNAL_ERROR.getCode();
        this.message = message;
    }

    public BusinessException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.code = errorCode.getCode();
        this.message = message;
    }
}
