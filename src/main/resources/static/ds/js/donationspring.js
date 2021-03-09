var donationspring = new function () {

  var current_step = 1,
    stripe = Stripe("pk_test_TYooMQauvdEDq54NiTphI7jx"),
    card = '',
    //modal = document.getElementById("ds_modal"),
    thankyou_msg = '';

  ds_initial_values = function (args) {
    amount_input = document.querySelectorAll('.amount');
    default_donation_type = args.default_donation_type || "onetime";
    form_type = args.form_type || "donation";
    default_amt = args.default_amt || 50;
    values = args.values || [25, 50, 100, 500, 1000, 1500, 2000];
    thankyou_msg = args.thankyou_msg || "<p>Thank you for your donation! You really help to make a difference!</p>";

    switch (default_donation_type) {
      case 'monthly':
        document.querySelectorAll('input[type=radio][name="donation_type"]')[1].checked = true;
        for (s = 0; s < amount_input.length; s++) {
          amount_input[s].setAttribute('data-is_month', '/month');
        }
        break;
      case 'onetime':
        document.querySelectorAll('input[type=radio][name="donation_type"]')[0].checked = true;
        for (s = 0; s < amount_input.length; s++) {
          amount_input[s].setAttribute('data-is_month', '');
        }
        break;
    }

    switch (form_type) {
      case 'donation':
        document.getElementById("ds_type-donation").style.display = "block";
        break;
      case 'campaign':
        document.getElementById("ds_type-campaign").style.display = "block";
        break;
      case 'event':
        document.getElementById("ds_type-event").style.display = "block";
        break;
      default:
        document.getElementById("ds_type-donation").style.display = "block";
    }

    var donation_amounts = document.getElementById("donation_amounts");
    document.getElementById("da_manual_amount").value = default_amt;
    document.getElementsByName("donation_amount")[0].value = default_amt;
    var giving_amount_display = document.getElementById("giving_amount");
    var giving_duration_display = document.getElementById("giving_duration");
    giving_amount_display.innerHTML = default_amt;
    giving_duration_display.innerHTML = ' ' + default_donation_type;
    for (v = 0; v < values.length; v++) {
      var amt_button = document.createElement('div');
      amt_button.className = 'button_radio-wrapper';
      if (default_amt == values[v]) {
        amt_button.innerHTML = '<input type="radio" id="da_' + values[v] + '" name="donation_amount_select" value="' + values[v] + '" checked> <label for="da_' + values[v] + '">$' + values[v] + '</label>';
      } else {
        amt_button.innerHTML = '<input type="radio" id="da_' + values[v] + '" name="donation_amount_select" value="' + values[v] + '"> <label for="da_' + values[v] + '">$' + values[v] + '</label>';
      }
      donation_amounts.appendChild(amt_button);
    }
    var other_button = document.createElement('div');
    other_button.className = 'button_radio-wrapper';
    other_button.innerHTML = '<input type="radio" id="da_other" name="donation_amount_select" value="other"> <label for="da_other">Other</label>';
    donation_amounts.appendChild(other_button);

    if (args.campaign) {
      document.getElementById("goal_thermometer").style.display = "block";
      document.getElementById("ds_raised_amt").childNodes[0].innerHTML = '$' + args.campaign.raised_amt;
      document.getElementById("ds_goal_amt").childNodes[1].innerHTML = '$' + args.campaign.goal_amt;
      var percent_complete = (args.campaign.raised_amt / args.campaign.goal_amt) * 100;
      document.getElementById("ds_bar-value").setAttribute("style", "width:" + percent_complete + "%");
    }

    if (args.sub_selection) {
      var options = args.sub_selection.options;
      var sub_selection = document.getElementById("donation_subselection");

      var select_wrap = document.createElement('div');
      select_wrap.classList.add("input-wrapper");
      sub_selection.appendChild(select_wrap);

      var selectListLabel = document.createElement("label");
      selectListLabel.setAttribute("for", 'subselection');
      selectListLabel.innerHTML = args.sub_selection.title + '<span class="req">*</span>';
      select_wrap.appendChild(selectListLabel);

      var selectList = document.createElement("select");
      selectList.dataset.required = true;
      selectList.id = "subselection";
      selectList.name = "sub_selection";
      var default_option = document.createElement("option");
      default_option.value = '';
      default_option.text = args.sub_selection.initial_option;
      selectList.appendChild(default_option);
      for (var i = 0; i < options.length; i++) {
        var option = document.createElement("option");
        option.value = options[i][1];
        option.text = options[i][0];
        selectList.appendChild(option);
      }
      select_wrap.appendChild(selectList);

      var selectListError = document.createElement("span");
      selectListError.classList.add("errorSpan");
      selectListError.innerHTML = 'This field is required.';
      select_wrap.appendChild(selectListError);
    }
  }

  this.init = function (args) {

    if (args) {
      ds_initial_values(args);
    }
    this.show_step(current_step);
    ds_triggers();

    var elements = stripe.elements();
    var style = {
      base: {
        fontSmoothing: 'antialiased',
        '::placeholder': {
          color: '#aab7c4'
        },
      },
      invalid: {
        color: '#fa755a',
        iconColor: '#fa755a'
      }
    };
    card = elements.create('card', { style: style });
    card.mount('#card-element');
    card.on('change', function (event) {
      var displayError = document.getElementById('card-errors');
      if (event.error) {
        displayError.textContent = event.error.message;
      } else {
        displayError.textContent = '';
      }
    });
    
  };

  this.show_step = function (n) {
    current_step = n;
    hide_class('ds_step');
    document.querySelectorAll('.ds_timeline li').forEach(function (item, index) {
      item.classList.remove("current", "past");
      if (index < current_step - 1) {
        item.classList.add("past");
      }
      if (index == current_step - 1){
        item.classList.add("current");
      }
    })
    document.querySelectorAll('.ds_step')[current_step-1].style.display = "block";
    if (current_step > 1){
      document.getElementById('ds_modal__title').style.display = "none";
      document.getElementById('ds_modal__back_button').style.display = "block";
    } else {
      document.getElementById('ds_modal__title').style.display = "block";
      document.getElementById('ds_modal__back_button').style.display = "none";
    }
    document.getElementById('ds-modal__content').scrollTop = 0;
  }

  this.next_prev = function (n) {
    if (n == 1 && !validateForm()) {
      return false;
    }
    let x = document.querySelectorAll('.ds_step');
    current_step = current_step + n;
    if (current_step > x.length) {
      return false;
    }
    document.querySelectorAll('.ds_step')[current_step - 1].style.display = "none";
    this.show_step(current_step);
  }

  this.isNumber = function (evt) {
    evt = (evt) ? evt : window.event;
    var charCode = (evt.which) ? evt.which : evt.keyCode;
    if (charCode > 31 && (charCode < 48 || charCode > 57)) {
      return false;
    }
    return true;
  }

  isVisible = function(e) {
    return !!(e.offsetWidth || e.offsetHeight || e.getClientRects().length);
  }

  validateForm = function() {
    var valid = true;
    var x = document.querySelector('.ds_step');
    var y = document.querySelectorAll('.ds_step')[current_step - 1].querySelectorAll("input:not([type=hidden]), select");

    for (i = 0; i < y.length; i++) {
      if (isVisible(y[i])){
        if (y[i].value == "" && y[i].getAttribute('data-required') == 'true') {
          y[i].parentElement.classList.add("invalid");
          valid = false;
        } else {
          y[i].parentElement.classList.remove("invalid");
        }
      }
    }

    if (valid) {
    } else {
      console.log('Not Valid');
    }
    return valid;
  }

  submitDonationForm = function(evt) {
    let ds_form = this;
    evt.preventDefault();
    if (!validateForm()) {
      return false;
    }

    stripe.createToken(card).then(function (result) {
      if (result.error) {
        var errorElement = document.getElementById('card-errors');
        errorElement.textContent = result.error.message;
        return false;
      } else {
        document.getElementsByName("stripe_token")[0].value = result.token.id;
        let formData = new FormData(ds_form);
        let parsedData = {};
        for (let name of formData) {
          if (typeof (parsedData[name[0]]) == "undefined") {
            let tempdata = formData.getAll(name[0]);
            if (tempdata.length > 1) {
              parsedData[name[0]] = tempdata;
            } else {
              parsedData[name[0]] = tempdata[0];
            }
          }
        }

        let options = {};
        switch (ds_form.method.toLowerCase()) {
          case 'post':
            options.body = JSON.stringify(parsedData);
          case 'get':
            options.method = ds_form.method;
            options.headers = { 'Content-Type': 'application/json' };
            break;
        }

        fetch(ds_form.action, options).then(r => r.json()).then(data => {
          if (data.success) {
            donationspring.next_prev(1);
            document.getElementById("thankyou_msg").innerHTML = thankyou_msg;
            document.getElementById("ds_modal__title").innerHTML = 'Donation Received';
            document.getElementById("ds_modal__back_button").style.display = "none";
            document.getElementById("ds_modal__title").style.display = "block";
            parent.ds.ds_submitted = true;
          }
        });
      }
    });
  }

  ds_triggers = function (){
    document.getElementById('donate_as_business').addEventListener('click', function (event) {
      document.querySelector('.modal__content').classList.toggle("business");
    });

    var donation_type_inputs = document.querySelectorAll('input[type=radio][name="donation_type"]');
    var giving_duration_display = document.getElementById("giving_duration");
    var amount_input = document.querySelectorAll('.amount');
    donation_type_inputs.forEach(type => type.addEventListener('input', function(){
      if (type.value == 'monthly'){
        for (s = 0; s < amount_input.length; s++) {
          amount_input[s].setAttribute('data-is_month', '/month');
        }
        giving_duration_display.innerHTML = ' monthly';
      } else {
        for (s = 0; s < amount_input.length; s++) {
          amount_input[s].setAttribute('data-is_month', '');
        }
        giving_duration_display.innerHTML = '';
      }
    }));

    var giving_amount_display = document.getElementById("giving_amount");
    var donation_amount_inputs = document.querySelectorAll('input[type=radio][name="donation_amount_select"]');
    donation_amount_inputs.forEach(amount => amount.addEventListener('input', function () {
      if (amount.value != 'other'){
        document.getElementById("da_manual_amount").value = amount.value;
        document.getElementsByName("donation_amount")[0].value = amount.value;
        giving_amount_display.innerHTML = amount.value;
      } else {
        document.getElementById("da_manual_amount").value = '';
        document.getElementsByName("donation_amount")[0].value = '';
        document.getElementById("da_manual_amount").focus();
      }
    }));

    document.getElementById("da_manual_amount").addEventListener('input', function () {
      var donation_value = this.value;

      document.getElementsByName("donation_amount")[0].value = donation_value;

      giving_amount_display.innerHTML = donation_value;
      for (i = 0; i < donation_amount_inputs.length; i++) {
        if (donation_amount_inputs[i].value === donation_value){
          donation_amount_inputs[i].checked = true;
          break;
        } else {
          donation_amount_inputs[donation_amount_inputs.length-1].checked = true;
        }
      };
    });

    document.getElementById("ds_form").addEventListener("submit", submitDonationForm);

  }

  show_class = function (elem) {
    let el = document.getElementsByClassName(elem);
    for (let i = 0; i < el.length; i++) {
      el[i].style.display = "block";
    }
  };

  hide_class = function (elem) {
    let el = document.getElementsByClassName(elem);
    for (let i = 0; i < el.length; i++) {
      el[i].style.display = "none";
    }
  };

  toggle_visible_class = function (elem) {
    let el = document.getElementsByClassName(elem);
    for (let i = 0; i < el.length; i++) {
      if (window.getComputedStyle(el[i]).display === 'block') {
        hide_class(el[i]);
      }
      show_class(el[i]);
    }
  };

  ds_trap_modal = function (modal){
    document.activeElement = null;
    const focusableElements = 'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])';
    const firstFocusableElement = modal.querySelectorAll(focusableElements)[0];
    const focusableContent = modal.querySelectorAll(focusableElements);
    const lastFocusableElement = focusableContent[focusableContent.length - 1];
    modal.addEventListener('keydown', function (e) {
      let isTabPressed = e.key === 'Tab';
      let isEscapePressed = e.key === 'Escape';
      if (!isTabPressed) {
        if (isEscapePressed){
          parent.ds.hideform();
        }
        return;
      }
      if (e.shiftKey) {
        if (document.activeElement === firstFocusableElement) {
          lastFocusableElement.focus();
          e.preventDefault();
        }
      } else {
        if (document.activeElement === lastFocusableElement) {
          firstFocusableElement.focus();
          e.preventDefault();
        }
      }
    });
  }

};