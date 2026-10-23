/** 켜진 단추에 붙이는 'on' 클래스 */
export const on = (className: string, active: boolean) => (active ? `${className} on` : className);
