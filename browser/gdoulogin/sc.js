function showSwitchList() {
    var showSwitchList=$("#school_switch_list_id");
    if(showSwitchList && showSwitchList.css('display')=='none'){
        showSwitchList.show();
        $(".g-school-switch .g-switch-arrow").css("transform", "rotate(90deg)");
    }else{
        showSwitchList.hide();
        $(".g-school-switch .g-switch-arrow").css("transform", "");
    }
}